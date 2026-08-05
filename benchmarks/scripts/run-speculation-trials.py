#!/usr/bin/env python3
"""Straggler-mitigation trials: speculation off vs on under an identical deterministic slowdown.

Each trial submits a build with a single task ("straggler:build", real javac/java/jar work, unique
cache key per trial so it always actually executes). Once a worker is genuinely running it, that
worker's container is `docker pause`d for a fixed STALL_SECONDS -- frozen, not killed, so the lease
stays valid and the only question is whether something else finishes the task faster. The pause is
identical in both arms; only FORGE_SCHEDULER_SPECULATION_ENABLED differs.

STALL_SECONDS is calibrated between two thresholds so the mechanism under test is unambiguous:
  - above the speculation threshold (so speculation, when enabled, has time to fire), and
  - below the worker-death threshold (heartbeat interval x 3, raised for this run), so a paused
    worker is never mistaken for a crashed one -- this measures speculation, not crash recovery.

Resumable: the result file is written after every trial.

Usage:
    python3 benchmarks/scripts/run-speculation-trials.py [--trials 30] [--out FILE]
"""
from __future__ import annotations

import argparse
import platform
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import docker_stack as ds  # noqa: E402

RAW = ds.REPO / "benchmarks" / "results" / "raw"
CHECKPOINT = RAW / "speculation-trials-checkpoint.json"
WORKERS = 4
STALL_SECONDS = 9
HISTORY_WARMUPS = 3  # gives TaskDurationEstimator a median to call anything slow relative to

BASE_ENV = {
    "FORGE_SEED_WORKSPACE_FROM": ds.SCALE_REPO_IN_IMAGE,
    "FORGE_WORKER_MAX_CONCURRENCY": "1",
    "FORGE_SCHEDULER_POLICY": "critical-path",
    # raised well past STALL_SECONDS x 3 so a paused-but-alive worker is never declared dead --
    # that would reclaim the lease via the crash-recovery path regardless of speculation, and
    # conflate the two mechanisms this trial is trying to tell apart
    "FORGE_WORKER_HEARTBEAT_INTERVAL_MS": "6000",
    "FORGE_SCHEDULER_SPECULATION_MIN_ELAPSED_MS": "3000",
    "FORGE_SCHEDULER_SPECULATION_MULTIPLIER": "1.5",
}


def straggler_task(revision: str) -> dict:
    module = ds.GEN.module_name(0, 0)
    return {
        "name": "straggler:build",
        "dependsOn": [],
        "cacheKey": f"sha256:{revision}-straggler",
        "reason": "speculation trial",
        "command": ["./scripts/task", "build", module],
        "outputs": [f"build/{module}/**"],
        "environment": ["JAVA_HOME"],
        "timeoutSeconds": 120,
    }


def submit(project_id: int, revision: str) -> int:
    plan = ds.request(
        "POST",
        f"/api/projects/{project_id}/plans",
        {
            "revision": revision,
            "baseRevision": "rev-base",
            "fullBuild": True,
            "changedPaths": [],
            "tasks": [straggler_task(revision)],
            "unaffectedTasks": [],
        },
        timeout=60,
    )
    build = ds.request(
        "POST",
        f"/api/projects/{project_id}/builds",
        {"planSubmissionId": plan["id"], "triggerType": "speculation-trial", "requestedWorkerCount": WORKERS},
        timeout=60,
    )
    return build["id"]


def busy_worker(build_id: int) -> str | None:
    result = ds.compose(
        [
            "exec", "-T", "mysql", "mysql", "-uforgeci", "-pforgeci", "--skip-column-names", "-e",
            "select w.external_id from forgeci.task_attempts a "
            "join forgeci.workers w on w.id = a.worker_id "
            "join forgeci.task_runs r on r.id = a.task_run_id "
            f"where r.build_id = {build_id} and a.state in ('LEASED','RUNNING') "
            "and a.speculative = 0 limit 1",
        ],
        check=False,
    )
    lines = [l.strip() for l in result.stdout.strip().splitlines() if l.strip()]
    return lines[0] if lines else None


def counters() -> dict:
    m = ds.prometheus()
    return {
        "submitted": ds.metric(m, "forge_results_submitted_total"),
        "accepted": ds.metric(m, "forge_results_accepted_total"),
        "rejected": ds.metric(m, "forge_results_rejected_total"),
        "speculative_started": ds.metric(m, 'forge_tasks_attempts_total{speculative="true"'),
    }


def seed_history(project_id: int):
    for i in range(HISTORY_WARMUPS):
        build_id = submit(project_id, f"rev-history-{i}-{int(time.time()*1000)}")
        ds.await_build(build_id, timeout=60)


def run_trial(name: str, index: int, speculation: bool) -> dict:
    # a fresh project (and a fresh 3-run seed) every trial, not one shared across the whole arm:
    # TaskDurationEstimator's median is computed over this project's own history, and a trial that
    # gets paused for STALL_SECONDS produces a genuinely inflated recorded duration once it finally
    # reports. Sharing one project let that inflated duration feed the *next* trial's median, which
    # after a handful of trials pushed the straggler threshold (median x multiplier) past
    # STALL_SECONDS itself -- speculation silently stopped firing for the rest of the run. Isolating
    # history per trial is a benchmark-methodology fix; TaskDurationEstimator itself is unchanged.
    project_id = ds.register_project(f"speculation-{name}-{index}-{int(time.time()*1000)}")
    seed_history(project_id)
    revision = f"rev-spec-{'on' if speculation else 'off'}-{index}-{int(time.time()*1000)}"
    build_id = submit(project_id, revision)
    before = counters()

    victim = None
    deadline = time.time() + 30
    while time.time() < deadline and victim is None:
        victim = busy_worker(build_id)
        if victim is None:
            time.sleep(0.15)
    if victim is None:
        return {"index": index, "skipped": "no worker ever claimed the task"}

    started_wall = time.perf_counter()
    ds.pause_worker(victim)
    resumed = [False]

    def on_poll(build, elapsed):
        if not resumed[0] and elapsed >= STALL_SECONDS:
            resumed[0] = True
            ds.unpause_worker(victim)

    try:
        result = ds.await_build(build_id, timeout=120, on_poll=on_poll)
    except RuntimeError as never_finished:
        if not resumed[0]:
            ds.unpause_worker(victim)
        return {"index": index, "victim": victim, "recovered": False, "error": str(never_finished)}

    if not resumed[0]:
        ds.unpause_worker(victim)
    # let the (possibly still-catching-up) original worker's late report land and be
    # accepted-or-rejected before reading counters, so the duplicate/accept split is complete
    time.sleep(2.0)
    after = counters()

    return {
        "index": index,
        "victim": victim,
        "recovered": result["build"]["state"] == "SUCCEEDED",
        "state": result["build"]["state"],
        "wall_ms": round((time.perf_counter() - started_wall) * 1000.0, 1),
        "results_submitted": after["submitted"] - before["submitted"],
        "results_accepted": after["accepted"] - before["accepted"],
        "duplicates_rejected": after["rejected"] - before["rejected"],
        "speculative_started": after["speculative_started"] - before["speculative_started"],
    }


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = (len(ordered) - 1) * p
    low, high = int(rank), min(int(rank) + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (rank - low)


def summarise(trials: list[dict]) -> dict:
    counted = [t for t in trials if "skipped" not in t]
    recovered = [t for t in counted if t.get("recovered")]
    latencies = [t["wall_ms"] for t in recovered]
    return {
        "trials": len(counted),
        "skipped": len(trials) - len(counted),
        "recovered": len(recovered),
        "build_latency_p50_ms": round(percentile(latencies, 0.50), 1),
        "build_latency_p95_ms": round(percentile(latencies, 0.95), 1),
        "speculative_attempts_started": sum(t.get("speculative_started", 0) for t in counted),
        "results_submitted": sum(t.get("results_submitted", 0) for t in counted),
        "results_accepted": sum(t.get("results_accepted", 0) for t in counted),
        "duplicates_rejected": sum(t.get("duplicates_rejected", 0) for t in counted),
    }


def run_arm(name: str, speculation: bool, trials_wanted: int, state: dict):
    arm_state = state["arms"].setdefault(name, {"trials": []})
    if len(arm_state["trials"]) >= trials_wanted:
        print(f"[{name}] already has {trials_wanted} trials, skipping", flush=True)
        arm_state["summary"] = summarise(arm_state["trials"])
        return

    env = dict(BASE_ENV)
    env["FORGE_SCHEDULER_SPECULATION_ENABLED"] = "true" if speculation else "false"
    print(f"[{name}] cold start (speculation={speculation})", flush=True)
    ds.down()
    ds.compose(["up", "-d", "mysql", "minio", "kafka", "redis"], env=env)
    ds.up(env, worker_count=WORKERS)

    while len(arm_state["trials"]) < trials_wanted:
        i = len(arm_state["trials"])
        trial = run_trial(name, i, speculation)
        arm_state["trials"].append(trial)
        ds.save_checkpoint(CHECKPOINT, state)
        flag = "ok" if trial.get("recovered") else ("skip" if "skipped" in trial else "FAIL")
        print(
            f"[{name}] trial {i + 1:3d}/{trials_wanted} {flag:4s} "
            f"{trial.get('wall_ms', 0) / 1000:.2f}s spec={trial.get('speculative_started', 0)} "
            f"dup={trial.get('duplicates_rejected', 0)}",
            flush=True,
        )

    arm_state["summary"] = summarise(arm_state["trials"])
    ds.save_checkpoint(CHECKPOINT, state)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trials", type=int, default=30, help="trials per arm")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    state = ds.load_checkpoint(CHECKPOINT)
    state.setdefault("kind", "docker-stack-speculation")
    state.setdefault("started_at", datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"))
    state.setdefault(
        "environment",
        {
            "profile": "local-benchmark",
            "workers": WORKERS,
            "stall_seconds": STALL_SECONDS,
            "platform": platform.platform(),
            "commit": subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=ds.REPO, capture_output=True, text=True
            ).stdout.strip(),
        },
    )
    state.setdefault("arms", {})

    try:
        run_arm("speculation-off", False, args.trials, state)
        run_arm("speculation-on", True, args.trials, state)
    finally:
        ds.down()

    out = Path(args.out) if args.out else RAW / (
        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-docker-speculation.json"
    )
    ds.save_checkpoint(out, state)
    print(f"wrote {out}")
    for name, arm in state["arms"].items():
        print(f"  {name}: {arm.get('summary')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
