#!/usr/bin/env python3
"""Worker-failure trials against the real Docker stack: SIGKILL a worker that owns an active task.

Each trial submits a small build, waits until a worker is genuinely executing one of its tasks,
SIGKILLs that container (no deregistration, no report -- the control plane must notice on its own
via missed heartbeats and reclaim the attempt), then measures whether and how fast the build still
reaches SUCCEEDED with every artifact intact.

A small, fixed graph is used throughout -- the question here is how reliably and how fast the
system recovers across many trials, not how large a graph it can execute. Graph size is measured
separately by run-docker-benchmarks.py.

Resumable: the result file is written after every trial, and completed trial indices are skipped on
re-invocation.

Usage:
    python3 benchmarks/scripts/run-fault-trials.py [--trials 50] [--out FILE]
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
CHECKPOINT = RAW / "fault-trials-checkpoint.json"
WORKERS = 4

ENV = {
    "FORGE_SEED_WORKSPACE_FROM": ds.SCALE_REPO_IN_IMAGE,
    "FORGE_WORKER_MAX_CONCURRENCY": "1",
    "FORGE_SCHEDULER_POLICY": "critical-path",
    "FORGE_SCHEDULER_SPECULATION_ENABLED": "false",
}


def small_graph(revision: str) -> list[dict]:
    """Twelve tasks (4 modules x build/test/package) over the scale fixture's own modules, so the
    commands are the same real javac/java/jar work the large benchmark runs, just fewer of them."""
    tasks = []
    for module in (ds.GEN.module_name(0, i) for i in range(4)):
        tasks.append(
            {
                "name": f"{module}:build",
                "dependsOn": [],
                "cacheKey": f"sha256:{revision}-{module}-build",
                "reason": "fault trial",
                "command": ["./scripts/task", "build", module],
                "outputs": [f"build/{module}/**"],
                "environment": ["JAVA_HOME"],
                "timeoutSeconds": 120,
            }
        )
        for kind in ("test", "package"):
            tasks.append(
                {
                    "name": f"{module}:{kind}",
                    "dependsOn": [f"{module}:build"],
                    "cacheKey": f"sha256:{revision}-{module}-{kind}",
                    "reason": "fault trial",
                    "command": ["./scripts/task", kind, module],
                    "outputs": [f"build/{module}/{kind}.txt"],
                    "environment": ["JAVA_HOME"],
                    "timeoutSeconds": 120,
                }
            )
    return tasks


def submit(project_id: int, revision: str) -> dict:
    tasks = small_graph(revision)
    plan = ds.request(
        "POST",
        f"/api/projects/{project_id}/plans",
        {
            "revision": revision,
            "baseRevision": "rev-base",
            "fullBuild": True,
            "changedPaths": [],
            "tasks": tasks,
            "unaffectedTasks": [],
        },
        timeout=60,
    )
    build = ds.request(
        "POST",
        f"/api/projects/{project_id}/builds",
        {"planSubmissionId": plan["id"], "triggerType": "fault-trial", "requestedWorkerCount": WORKERS},
        timeout=60,
    )
    return {"build": build, "task_count": len(tasks)}


def busy_worker(build_id: int) -> str | None:
    """The compose service name of a worker currently holding a live attempt on this build.

    Injecting into an idle worker would prove nothing, so a trial that cannot find one within the
    wait budget is recorded as skipped rather than counted as either a success or a failure.
    """
    result = ds.compose(
        [
            "exec", "-T", "mysql", "mysql", "-uforgeci", "-pforgeci", "--skip-column-names", "-e",
            "select w.external_id from forgeci.task_attempts a "
            "join forgeci.workers w on w.id = a.worker_id "
            "join forgeci.task_runs r on r.id = a.task_run_id "
            f"where r.build_id = {build_id} and a.state in ('LEASED','RUNNING') limit 1",
        ],
        check=False,
    )
    lines = [l.strip() for l in result.stdout.strip().splitlines() if l.strip()]
    return lines[0] if lines else None


def artifacts_intact(build_id: int, expected: int) -> bool:
    """Every succeeded task run of a recovered build must carry the digest of the artifact its
    winning attempt produced -- a missing digest would mean a result was accepted without bytes."""
    result = ds.compose(
        [
            "exec", "-T", "mysql", "mysql", "-uforgeci", "-pforgeci", "--skip-column-names", "-e",
            "select count(*) from forgeci.task_runs "
            f"where build_id = {build_id} and state = 'SUCCEEDED' and artifact_digest is not null",
        ],
        check=False,
    )
    line = result.stdout.strip()
    return line.isdigit() and int(line) == expected


def counters() -> dict:
    m = ds.prometheus()
    return {
        "submitted": ds.metric(m, "forge_results_submitted_total"),
        "accepted": ds.metric(m, "forge_results_accepted_total"),
        "rejected": ds.metric(m, "forge_results_rejected_total"),
    }


def run_trial(project_id: int, index: int) -> dict:
    revision = f"rev-fault-{index}-{int(time.time()*1000)}"
    submitted = submit(project_id, revision)
    build_id = submitted["build"]["id"]
    before = counters()

    victim = None
    deadline = time.time() + 60
    while time.time() < deadline and victim is None:
        victim = busy_worker(build_id)
        if victim is None:
            time.sleep(0.2)
    if victim is None:
        return {"index": index, "skipped": "no worker ever became busy"}

    injected_at = time.perf_counter()
    ds.kill_worker(victim)

    try:
        result = ds.await_build(build_id, timeout=180)
    except RuntimeError as never_finished:
        ds.start_worker(victim, ENV)
        return {"index": index, "victim": victim, "recovered": False, "error": str(never_finished)}

    recovery_ms = (time.perf_counter() - injected_at) * 1000.0
    after = counters()
    succeeded = result["build"]["state"] == "SUCCEEDED"
    trial = {
        "index": index,
        "victim": victim,
        "recovered": succeeded,
        "state": result["build"]["state"],
        "recovery_ms": round(recovery_ms, 1),
        "artifacts_intact": artifacts_intact(build_id, submitted["task_count"]) if succeeded else False,
        "results_submitted": after["submitted"] - before["submitted"],
        "results_accepted": after["accepted"] - before["accepted"],
        "duplicates_rejected": after["rejected"] - before["rejected"],
    }
    ds.start_worker(victim, ENV)
    try:
        ds.await_workers(WORKERS, timeout=60)
    except RuntimeError:
        pass  # a worker slow to rejoin is visible in the next trial's own wait, never masked
    return trial


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
    latencies = [t["recovery_ms"] for t in recovered]
    return {
        "trials": len(counted),
        "skipped": len(trials) - len(counted),
        "recovered": len(recovered),
        "recovery_rate": round(len(recovered) / len(counted), 4) if counted else 0.0,
        "artifact_correct": sum(1 for t in recovered if t["artifacts_intact"]),
        "recovery_p50_ms": round(percentile(latencies, 0.50), 1),
        "recovery_p95_ms": round(percentile(latencies, 0.95), 1),
        "recovery_max_ms": round(max(latencies), 1) if latencies else 0.0,
        "results_submitted": sum(t.get("results_submitted", 0) for t in counted),
        "results_accepted": sum(t.get("results_accepted", 0) for t in counted),
        "duplicates_rejected": sum(t.get("duplicates_rejected", 0) for t in counted),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trials", type=int, default=50)
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    state = ds.load_checkpoint(CHECKPOINT)
    state.setdefault("kind", "docker-stack-fault-injection")
    state.setdefault("started_at", datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"))
    state.setdefault(
        "environment",
        {
            "profile": "local-benchmark",
            "note": "single developer machine, Docker Desktop; not a dedicated benchmark host",
            "platform": platform.platform(),
            "workers": WORKERS,
            "fault": "SIGKILL on a worker holding a live LEASED/RUNNING attempt",
            "commit": subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=ds.REPO, capture_output=True, text=True
            ).stdout.strip(),
        },
    )
    state.setdefault("trials", [])

    if len(state["trials"]) < args.trials:
        print(f"cold start, {args.trials} trials requested, {len(state['trials'])} already done", flush=True)
        ds.down()
        ds.compose(["up", "-d", "mysql", "minio", "kafka", "redis"], env=ENV)
        ds.up(ENV, worker_count=WORKERS)
        project = ds.register_project(f"fault-{int(time.time())}")

        try:
            while len(state["trials"]) < args.trials:
                i = len(state["trials"])
                trial = run_trial(project, i)
                state["trials"].append(trial)
                ds.save_checkpoint(CHECKPOINT, state)
                flag = "ok" if trial.get("recovered") else ("skip" if "skipped" in trial else "FAIL")
                print(
                    f"trial {i + 1:3d}/{args.trials} {flag:4s} "
                    f"{trial.get('recovery_ms', 0) / 1000:.2f}s "
                    f"dup={trial.get('duplicates_rejected', 0)}",
                    flush=True,
                )
        finally:
            ds.down()
    else:
        print(f"already have {len(state['trials'])} trials, nothing to do", flush=True)

    state["summary"] = summarise(state["trials"])
    out = Path(args.out) if args.out else RAW / (
        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-docker-fault-trials.json"
    )
    ds.save_checkpoint(out, state)
    print(f"wrote {out}")
    print(state["summary"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
