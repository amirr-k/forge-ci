#!/usr/bin/env python3
"""Verifies the remote content-addressed cache path end to end against the real Docker stack.

1. Submit a build (fixed cache keys) so its tasks actually execute and their artifacts land in
   MinIO, and record each artifact's digest.
2. Tear down and recreate the worker containers with a fresh named volume -- no worker carries any
   state from step 1 forward, so nothing in step 3 can come from worker-local state.
3. Submit a second build with the identical cache keys. Every task run must resolve as CACHED
   without ever being claimed by a worker (`SchedulerService.promoteToReadyOrCached`) -- the
   control plane's S3-compatible MinIO lookup, not any worker, is what supplies the result.
4. Independently re-download each artifact via `/api/artifacts/lookup` and re-hash the bytes in
   Python, comparing against the digest recorded in step 1 -- proving the restored artifact is
   byte-identical, not just that the database row says so.

Usage:
    python3 benchmarks/scripts/run-remote-cache-check.py [--out FILE]
"""
from __future__ import annotations

import argparse
import hashlib
import platform
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import docker_stack as ds  # noqa: E402

RAW = ds.REPO / "benchmarks" / "results" / "raw"
WORKERS = 2
MODULE_COUNT = 6  # first few modules of layer 0 -- enough to exercise real fan-out, fast to run

ENV = {
    "FORGE_SEED_WORKSPACE_FROM": ds.SCALE_REPO_IN_IMAGE,
    "FORGE_WORKER_MAX_CONCURRENCY": "1",
}


def graph(revision: str) -> list[dict]:
    tasks = []
    for i in range(MODULE_COUNT):
        module = ds.GEN.module_name(0, i)
        tasks.append(
            {
                "name": f"{module}:build",
                "dependsOn": [],
                # fixed, not salted by time -- the whole point is that build 2 reuses build 1's keys
                "cacheKey": f"sha256:{revision}-{module}-build",
                "reason": "remote cache check",
                "command": ["./scripts/task", "build", module],
                "outputs": [f"build/{module}/**"],
                "environment": ["JAVA_HOME"],
                "timeoutSeconds": 120,
            }
        )
    return tasks


def submit(project_id: int, plan_revision: str, cache_key_revision: str) -> dict:
    """`plan_revision` identifies this plan submission (and so this build); `cache_key_revision`
    is baked into every task's cache key.

    These are deliberately independent: plan submission and build creation are both idempotent on
    (project, revision) / (plan submission), so resubmitting the *same* plan_revision for build 2
    would return build 1's own row unchanged rather than creating a genuinely new build that has to
    resolve its tasks via the cache -- which would make this check pass by definition rather than
    by actually exercising the cache path. Build 2 uses a distinct plan_revision but the identical
    cache_key_revision, so its task runs are new rows that must independently look up the same keys.
    """
    tasks = graph(cache_key_revision)
    plan = ds.request(
        "POST",
        f"/api/projects/{project_id}/plans",
        {
            "revision": plan_revision,
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
        {"planSubmissionId": plan["id"], "triggerType": "remote-cache-check", "requestedWorkerCount": WORKERS},
        timeout=60,
    )
    return {"build": build, "tasks": tasks}


def task_run_rows(build_id: int) -> list[dict]:
    result = ds.compose(
        [
            "exec", "-T", "mysql", "mysql", "-uforgeci", "-pforgeci", "--skip-column-names", "-e",
            "select task_name, state, ifnull(artifact_digest,'') , ifnull(worker_id,0) "
            f"from forgeci.task_runs where build_id = {build_id} order by task_name",
        ],
        check=False,
    )
    rows = []
    for line in result.stdout.strip().splitlines():
        parts = line.split("\t")
        if len(parts) == 4:
            rows.append(
                {
                    "task_name": parts[0],
                    "state": parts[1],
                    "artifact_digest": parts[2],
                    "worker_id": int(parts[3]),
                }
            )
    return rows


def independent_sha256(project_id: int, cache_key: str) -> str:
    """Re-downloads the artifact through the public lookup API and hashes the bytes locally --
    verifying restored content, not trusting the digest column alone."""
    req = urllib.request.Request(
        f"{ds.BASE_URL}/api/artifacts/lookup?projectId={project_id}&cacheKey={cache_key}"
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        content = response.read()
        server_digest = response.headers.get("X-Artifact-Digest")
    return {"sha256": hashlib.sha256(content).hexdigest(), "server_digest": server_digest, "bytes": len(content)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    started = datetime.now(timezone.utc)
    ds.down()
    ds.compose(["up", "-d", "mysql", "minio", "kafka", "redis"], env=ENV)
    ds.up(ENV, worker_count=WORKERS)

    project = ds.register_project(f"remote-cache-{int(time.time())}")
    cache_key_revision = f"rev-remote-cache-{int(time.time())}"

    print("build 1: cold, populates MinIO", flush=True)
    first = submit(project, f"{cache_key_revision}-build1", cache_key_revision)
    result1 = ds.await_build(first["build"]["id"], timeout=300)
    rows1 = task_run_rows(first["build"]["id"])
    assert result1["build"]["state"] == "SUCCEEDED", f"build 1 did not succeed: {result1['build']}"
    assert all(r["state"] == "SUCCEEDED" for r in rows1), rows1
    digests_by_task = {r["task_name"]: r["artifact_digest"] for r in rows1}
    workers_used = {r["worker_id"] for r in rows1}
    print(f"  succeeded, {len(rows1)} tasks, workers used: {sorted(workers_used)}", flush=True)

    print("recreating worker containers with a fresh workspace volume", flush=True)
    ds.compose(["rm", "-f", "-s", "worker-1", "worker-2"], env=ENV, check=False)
    ds.compose(["volume", "rm", "forgeci_worker-workspace"], check=False)
    ds.compose(["up", "-d", "worker-1", "worker-2"], env=ENV)
    ds.await_workers(WORKERS, timeout=120)

    print("build 2: identical cache keys, must resolve from the remote cache alone", flush=True)
    # a genuinely new plan submission/build (different plan_revision) but identical cache keys
    second = submit(project, f"{cache_key_revision}-build2", cache_key_revision)
    result2 = ds.await_build(second["build"]["id"], timeout=120)
    rows2 = task_run_rows(second["build"]["id"])
    assert result2["build"]["state"] == "SUCCEEDED", f"build 2 did not succeed: {result2['build']}"

    all_cached = all(r["state"] == "CACHED" for r in rows2)
    no_worker_claimed = all(r["worker_id"] == 0 for r in rows2)
    digests_match = all(r["artifact_digest"] == digests_by_task[r["task_name"]] for r in rows2)
    print(
        f"  all CACHED: {all_cached}, no worker claimed: {no_worker_claimed}, "
        f"digests match build 1: {digests_match}",
        flush=True,
    )

    print("independently re-hashing every restored artifact", flush=True)
    verifications = []
    all_verified = True
    for task in first["tasks"]:
        check = independent_sha256(project, task["cacheKey"])
        recorded = digests_by_task[task["name"]]
        # the recorded digest carries a "sha256:" prefix; the lookup header and local rehash don't
        matches = check["sha256"] in recorded and check["server_digest"] in recorded
        all_verified = all_verified and matches
        verifications.append(
            {
                "task": task["name"],
                "recorded_digest": recorded,
                "server_digest_header": check["server_digest"],
                "locally_rehashed_sha256": check["sha256"],
                "bytes": check["bytes"],
                "matches": matches,
            }
        )
        print(f"  {task['name']}: matches={matches} ({check['bytes']} bytes)", flush=True)

    ds.down()

    payload = {
        "kind": "docker-stack-remote-cache",
        "started_at": started.isoformat().replace("+00:00", "Z"),
        "environment": {
            "profile": "local-benchmark",
            "platform": platform.platform(),
            "workers": WORKERS,
            "commit": subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=ds.REPO, capture_output=True, text=True
            ).stdout.strip(),
        },
        "task_count": len(first["tasks"]),
        "build_1_all_succeeded": all(r["state"] == "SUCCEEDED" for r in rows1),
        "build_1_workers_used": sorted(workers_used),
        "build_2_all_cached": all_cached,
        "build_2_no_worker_claimed": no_worker_claimed,
        "build_2_digests_match_build_1": digests_match,
        "independent_rehash_all_match": all_verified,
        "verifications": verifications,
        "passed": all(
            [
                all(r["state"] == "SUCCEEDED" for r in rows1),
                all_cached,
                no_worker_claimed,
                digests_match,
                all_verified,
            ]
        ),
    }

    out = Path(args.out) if args.out else RAW / (
        started.strftime("%Y%m%dT%H%M%SZ") + "-docker-remote-cache.json"
    )
    ds.save_checkpoint(out, payload)
    print(f"wrote {out}")
    print(f"PASSED: {payload['passed']}")
    return 0 if payload["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
