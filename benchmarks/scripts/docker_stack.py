"""Drives the real deploy/compose.yaml stack for benchmark and fault-injection runs.

Everything here talks to the same Kafka/Redis/MySQL/MinIO/control-plane/worker stack a deployment
uses -- no in-process shortcut, no mocked worker. A build measured through this module was executed
by Docker worker containers claiming over HTTP, exactly as in production.

The task graph is imported from generate-scale-fixture rather than parsed back out of the generated
forgeci.yml, so the benchmark and the fixture can never disagree about what the graph is.
"""
from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
COMPOSE = REPO / "deploy" / "compose.yaml"
BASE_URL = "http://localhost:8080"

# where the scale fixture is baked inside both images (apps/*/Dockerfile)
SCALE_REPO_IN_IMAGE = "/opt/forge-scale-repo"


def _load_generator():
    path = REPO / "benchmarks" / "scripts" / "generate-scale-fixture.py"
    spec = importlib.util.spec_from_file_location("generate_scale_fixture", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GEN = _load_generator()


def scale_task_graph(revision: str) -> list[dict]:
    """The 150-task graph of the scale fixture, as plan-submission task definitions.

    Cache keys are salted with `revision` so every trial is a genuine cold execution rather than a
    cache hit on the previous trial's artifacts -- a benchmark that silently measured cache lookups
    would report throughput the executor never achieved.
    """
    tasks = []
    for layer in range(GEN.LAYERS):
        for index in range(GEN.PER_LAYER):
            module = GEN.module_name(layer, index)
            deps = GEN.dependencies(layer, index)
            tasks.append(
                _task(module, "build", [f"{d}:build" for d in deps], f"build/{module}/**", revision)
            )
            tasks.append(
                _task(
                    module,
                    "test",
                    [f"{module}:build"],
                    f"build/{module}/test.txt",
                    revision,
                )
            )
            tasks.append(
                _task(
                    module,
                    "package",
                    [f"{module}:build"],
                    f"build/{module}/package.txt",
                    revision,
                )
            )
    return tasks


def _task(module: str, kind: str, depends: list[str], output: str, revision: str) -> dict:
    return {
        "name": f"{module}:{kind}",
        "dependsOn": depends,
        "cacheKey": f"sha256:{revision}-{module}-{kind}",
        "reason": "benchmark cold build",
        "command": ["./scripts/task", kind, module],
        "outputs": [output],
        "environment": ["JAVA_HOME"],
        "timeoutSeconds": 300,
    }


# --- HTTP -----------------------------------------------------------------------------------


def request(method: str, path: str, body=None, timeout: float = 30.0):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        BASE_URL + path, data=data, method=method, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def prometheus() -> dict[str, float]:
    """Scrapes the control plane's own counters. These are the authority for duplicate/accepted
    result counts -- they are incremented by the code path that actually accepted or rejected a
    result, not inferred afterwards from logs."""
    with urllib.request.urlopen(BASE_URL + "/actuator/prometheus", timeout=30) as response:
        text = response.read().decode()
    metrics = {}
    for line in text.splitlines():
        if line.startswith("#") or " " not in line:
            continue
        name, _, value = line.rpartition(" ")
        try:
            metrics[name.strip()] = float(value)
        except ValueError:
            continue
    return metrics


def metric(metrics: dict[str, float], prefix: str) -> float:
    """Sums every labelled series whose name starts with `prefix`."""
    return sum(v for k, v in metrics.items() if k.startswith(prefix))


# --- compose --------------------------------------------------------------------------------


def compose(args: list[str], env: dict | None = None, check: bool = True, capture: bool = True):
    full_env = {**os.environ, **(env or {})}
    return subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE), *args],
        env=full_env,
        capture_output=capture,
        text=True,
        check=check,
    )


def down():
    """-v matters: it drops the MySQL and MinIO volumes, which is what makes the next arm a
    genuinely cold run rather than one warmed by the previous arm's artifacts.

    COMPOSE_PROFILES matters just as much: `docker compose down` leaves profile-gated services
    running when their profile is inactive, so without this the previous arm's worker-3/worker-4
    survive into the next one and a "1 worker" arm silently runs with four.
    """
    compose(["down", "-v", "--remove-orphans"], env={"COMPOSE_PROFILES": "scale-4"}, check=False)


def up(arm_env: dict, worker_count: int, build_images: bool = False):
    env = dict(arm_env)
    # every benchmark/fault-injection worker is seeded from the scale fixture, not the demo repo,
    # which makes the control plane's own startup warm-up build fail and retry against the shared
    # worker pool -- unrelated contention right at the start of every measured run. The caller can
    # still override this by setting the key itself in arm_env before calling up().
    env.setdefault("FORGE_DEMO_WARMUP_ENABLED", "false")
    if worker_count > 2:
        env["COMPOSE_PROFILES"] = "scale-4"
    workers = [f"worker-{i}" for i in range(1, worker_count + 1)]
    flags = ["--build"] if build_images else []
    compose(["up", "-d", *flags, "control-plane"], env=env)
    await_ready()
    compose(["up", "-d", *flags, *workers], env=env)
    await_workers(worker_count)


def await_ready(timeout: float = 300.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            request("GET", "/api/ready", timeout=5)
            return
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError):
            time.sleep(2)
    raise RuntimeError("control plane never became ready")


def await_workers(expected: int, timeout: float = 300.0, exact: bool = True):
    """Waits until exactly `expected` workers are ACTIVE.

    Exact, not at-least: a leftover worker from a previous arm would make a "1 worker" arm run with
    four and quietly invalidate the scaling figure, and an at-least check would wave that through.
    """
    deadline = time.time() + timeout
    active = -1
    while time.time() < deadline:
        active = metric(prometheus(), "forge_workers_active")
        if (active == expected) if exact else (active >= expected):
            return
        time.sleep(2)
    raise RuntimeError(f"{active} workers active, expected {expected}")


# --- builds ---------------------------------------------------------------------------------


def register_project(name: str) -> int:
    return request(
        "POST",
        "/api/projects",
        {
            "name": name,
            "repositoryIdentity": f"git@example.com:forgeci/{name}.git",
            "defaultBranch": "main",
            "configVersion": 1,
        },
    )["id"]


def submit_build(project_id: int, revision: str, worker_count: int) -> dict:
    tasks = scale_task_graph(revision)
    plan = request(
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
        timeout=120,
    )
    build = request(
        "POST",
        f"/api/projects/{project_id}/builds",
        {"planSubmissionId": plan["id"], "triggerType": "benchmark", "requestedWorkerCount": worker_count},
        timeout=120,
    )
    return {"build": build, "task_count": len(tasks)}


TERMINAL = {"SUCCEEDED", "FAILED", "CANCELED"}


def await_build(build_id: int, timeout: float = 1800.0, on_poll=None) -> dict:
    """Polls to a terminal state and returns the build plus measured wall-clock milliseconds.

    Wall clock is measured here rather than read from the build's own timestamps so the number
    includes everything a user waits through -- scheduling, claim latency, and execution alike.
    """
    started = time.perf_counter()
    deadline = time.time() + timeout
    while time.time() < deadline:
        build = request(f"GET", f"/api/builds/{build_id}")
        if on_poll is not None:
            on_poll(build, time.perf_counter() - started)
        if build["state"] in TERMINAL:
            return {"build": build, "wall_ms": (time.perf_counter() - started) * 1000.0}
        time.sleep(0.5)
    raise RuntimeError(f"build {build_id} never reached a terminal state")


# --- fault injection ------------------------------------------------------------------------


def workers() -> dict[str, int]:
    """external id -> control-plane worker id, read from MySQL.

    There is deliberately no list-workers HTTP endpoint (the public API surface is fixed in
    contracts.md), and inventing one just for a benchmark would change the product to suit the
    measurement. Reading the authoritative table directly does not.
    """
    result = compose(
        [
            "exec",
            "-T",
            "mysql",
            "mysql",
            "-uforgeci",
            "-pforgeci",
            "--skip-column-names",
            "-e",
            "select external_id, id from forgeci.workers",
        ]
    )
    found = {}
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            found[parts[0]] = int(parts[1])
    return found


def kill_worker(service: str):
    """SIGKILL, so the worker gets no chance to deregister or report -- the control plane has to
    notice on its own, which is the whole point of the exercise."""
    compose(["kill", "-s", "KILL", service], check=False)


def pause_worker(service: str):
    """Freezes every process in the container: the worker stops heartbeating and stops reporting,
    but its in-flight task is not lost. On unpause it tries to report a result that may by then
    have been superseded -- the stale-result path."""
    compose(["pause", service], check=False)


def unpause_worker(service: str):
    compose(["unpause", service], check=False)


def start_worker(service: str, arm_env: dict):
    env = dict(arm_env)
    if service in ("worker-3", "worker-4"):
        env["COMPOSE_PROFILES"] = "scale-4"
    compose(["up", "-d", service], env=env, check=False)


# --- resumable checkpointing ------------------------------------------------------------------
#
# Every long-running harness below writes its result file after each unit of work (one arm, one
# trial), not once at the end -- a run killed by a wall-clock limit still leaves every completed
# unit on disk. Re-running the same script loads what is already there and continues past it
# instead of redoing it.


def load_checkpoint(path: Path) -> dict:
    if path.exists():
        return json.loads(path.read_text())
    return {}


def save_checkpoint(path: Path, payload: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)  # atomic on POSIX -- a reader never sees a half-written file
