#!/usr/bin/env python3
"""Cold-cache Docker-stack scheduler comparison over the 150-task scale fixture.

FIFO vs duration-aware critical-path, both at 4 workers with cleared caches, same graph, same
machine. Every task is claimed over HTTP by a Docker worker container and executed in its own
sandbox container -- not the in-process local-executor path `run-benchmarks.py` measures, and the
two are never reported as comparable.

Each measured trial takes well under five minutes, so this runs TRIALS_PER_ARM measured trials per
arm (median and full range reported), preceded by one discarded warm-up per arm.

Resumable: the result file is written after every completed trial, and a trial index already
present in it is skipped on the next invocation -- a run killed partway through loses nothing
already measured.

Usage:
    python3 benchmarks/scripts/run-docker-benchmarks.py [--trials 10] [--out FILE]
"""
from __future__ import annotations

import argparse
import platform
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import docker_stack as ds  # noqa: E402

RAW = ds.REPO / "benchmarks" / "results" / "raw"
CHECKPOINT = RAW / "docker-scheduling-checkpoint.json"

ARMS = [
    {"id": "fifo-w4", "policy": "fifo", "workers": 4},
    {"id": "critical-path-duration-w4", "policy": "critical-path-duration", "workers": 4},
]


def arm_env(arm: dict) -> dict:
    return {
        "FORGE_SEED_WORKSPACE_FROM": ds.SCALE_REPO_IN_IMAGE,
        # one task per worker, so "N workers" means exactly N tasks may run at once
        "FORGE_WORKER_MAX_CONCURRENCY": "1",
        "FORGE_SCHEDULER_POLICY": arm["policy"],
        "FORGE_SCHEDULER_SPECULATION_ENABLED": "false",
    }


def task_state_counts(build_id: int) -> dict[str, int]:
    result = ds.compose(
        [
            "exec", "-T", "mysql", "mysql", "-uforgeci", "-pforgeci", "--skip-column-names", "-e",
            f"select state, count(*) from forgeci.task_runs where build_id={build_id} group by state",
        ],
        check=False,
    )
    counts = {}
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            counts[parts[0]] = int(parts[1])
    return counts


def run_one_build(arm: dict, label: str) -> dict:
    project = ds.register_project(f"scale-{arm['id']}-{int(time.time()*1000)}")
    revision = f"rev-{arm['id']}-{label}-{int(time.time()*1000)}"
    submitted = ds.submit_build(project, revision, arm["workers"])
    build_id = submitted["build"]["id"]

    last = [0.0]

    def progress(build, elapsed):
        if elapsed - last[0] >= 30:
            last[0] = elapsed
            print(f"[{arm['id']}/{label}]   {elapsed:6.0f}s {build['state']}", flush=True)

    # trials normally finish in under two minutes; five is a generous margin that still fails fast
    # if the stack ever wedges, instead of burning the old 30-minute ceiling on a build going nowhere
    result = ds.await_build(build_id, timeout=300, on_poll=progress)
    metrics = ds.prometheus()
    counts = task_state_counts(build_id)
    return {
        "label": label,
        "task_count": submitted["task_count"],
        "state": result["build"]["state"],
        "wall_ms": round(result["wall_ms"], 1),
        "tasks_succeeded": counts.get("SUCCEEDED", 0),
        "tasks_failed": counts.get("FAILED", 0),
        "results_submitted": ds.metric(metrics, "forge_results_submitted_total"),
        "results_accepted": ds.metric(metrics, "forge_results_accepted_total"),
        "duplicates_rejected": ds.metric(metrics, "forge_results_rejected_total"),
    }


def summarise(trials: list[dict]) -> dict:
    good = [t for t in trials if t["state"] == "SUCCEEDED"]
    times = [t["wall_ms"] for t in good]
    return {
        "trials": len(trials),
        "succeeded": len(good),
        "median_ms": round(statistics.median(times), 1) if times else None,
        "min_ms": round(min(times), 1) if times else None,
        "max_ms": round(max(times), 1) if times else None,
    }


def run_one_build_with_retry(arm: dict, label: str, env: dict, max_attempts: int = 3) -> dict:
    """A build that never reaches a terminal state within `run_one_build`'s timeout is treated as a
    wedged stack, not a measurement -- it is discarded (never recorded as a trial) and the whole
    stack for this arm is recreated before trying again. This is what lets a single unattended
    invocation finish an arm rather than needing a human to notice a hang and restart the script."""
    for attempt in range(1, max_attempts + 1):
        try:
            return run_one_build(arm, label)
        except RuntimeError as wedged:
            print(f"[{arm['id']}/{label}] attempt {attempt} wedged ({wedged}), recreating stack", flush=True)
            if attempt == max_attempts:
                raise
            ds.down()
            ds.compose(["up", "-d", "mysql", "minio", "kafka", "redis"], env=env)
            ds.up(env, worker_count=arm["workers"])


def run_arm(arm: dict, trials_per_arm: int, state: dict):
    env = arm_env(arm)
    arm_state = state["arms"].setdefault(arm["id"], {"warmup": None, "trials": []})

    needs_start = arm_state["warmup"] is None or len(arm_state["trials"]) < trials_per_arm
    if not needs_start:
        print(f"[{arm['id']}] already has {trials_per_arm} trials, skipping", flush=True)
        arm_state["summary"] = summarise(arm_state["trials"])
        return

    # one stack for the whole arm, not one per trial: cold execution comes from every trial using
    # a unique cache-key revision (no prior trial ever wrote that key), not from tearing MySQL/
    # Kafka/the workers down and back up between every single build. A stack cycled 10+ times back
    # to back on a resource-constrained laptop was observed to eventually wedge (a build that never
    # progresses) -- keeping it up removes that churn without weakening what "cold" means here.
    print(f"[{arm['id']}] cold start", flush=True)
    ds.down()
    ds.compose(["up", "-d", "mysql", "minio", "kafka", "redis"], env=env)
    ds.up(env, worker_count=arm["workers"])

    if arm_state["warmup"] is None:
        print(f"[{arm['id']}] warm-up (discarded)", flush=True)
        arm_state["warmup"] = run_one_build_with_retry(arm, "warmup", env)
        ds.save_checkpoint(CHECKPOINT, state)

    while len(arm_state["trials"]) < trials_per_arm:
        idx = len(arm_state["trials"])
        print(f"[{arm['id']}] trial {idx + 1}/{trials_per_arm}", flush=True)
        ds.await_workers(arm["workers"], timeout=60)  # catch a wedged stack before submitting into it
        trial = run_one_build_with_retry(arm, f"trial-{idx}", env)
        arm_state["trials"].append(trial)
        ds.save_checkpoint(CHECKPOINT, state)
        print(
            f"[{arm['id']}] trial {idx + 1}: {trial['state']} in {trial['wall_ms']/1000:.1f}s",
            flush=True,
        )

    arm_state["summary"] = summarise(arm_state["trials"])
    ds.save_checkpoint(CHECKPOINT, state)


def environment() -> dict:
    return {
        "profile": "local-benchmark",
        "note": "single developer machine, Docker Desktop; not a dedicated benchmark host",
        "platform": platform.platform(),
        "cpu_count": __import__("os").cpu_count(),
        "commit": subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ds.REPO, capture_output=True, text=True
        ).stdout.strip(),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trials", type=int, default=10)
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    state = ds.load_checkpoint(CHECKPOINT)
    state.setdefault("kind", "docker-stack-scheduling")
    state.setdefault("started_at", datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"))
    state.setdefault("environment", environment())
    state.setdefault("arms", {})
    for arm in ARMS:
        state["arms"].setdefault(arm["id"], {"warmup": None, "trials": []})

    try:
        for arm in ARMS:
            try:
                run_arm(arm, args.trials, state)
            except RuntimeError as stack_trouble:
                # one arm wedging (a build that never progresses) must not cost the other arm its
                # own trials -- record the failure and move on; the wedged trial itself was never
                # appended to arm_state, so re-running this script retries only that trial
                print(f"[{arm['id']}] FAILED: {stack_trouble}", flush=True)
                state["arms"][arm["id"]]["error"] = str(stack_trouble)
                ds.save_checkpoint(CHECKPOINT, state)
                ds.down()  # the next arm's run_arm brings its own fresh stack up
    finally:
        ds.down()

    out = Path(args.out) if args.out else RAW / (
        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-docker-scheduling.json"
    )
    payload = dict(state)
    payload["arms"] = [
        {"arm": arm["id"], "policy": arm["policy"], "workers": arm["workers"], **state["arms"][arm["id"]]}
        for arm in ARMS
    ]
    ds.save_checkpoint(out, payload)
    print(f"wrote {out}")
    for arm in payload["arms"]:
        s = arm.get("summary", {})
        print(
            f"  {arm['arm']}: median={s.get('median_ms', 0)/1000:.1f}s "
            f"range=[{s.get('min_ms', 0)/1000:.1f}s, {s.get('max_ms', 0)/1000:.1f}s] "
            f"n={s.get('trials', 0)}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
