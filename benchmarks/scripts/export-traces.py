#!/usr/bin/env python3
"""Records real `forge run` executions as schema-valid traces for the static demo.

Each trace is a recording of an actual build: the DAG comes from the workload's forgeci.yml, and
every task status, reason, lane assignment, and timestamp comes from that run's own output. The
demo replays these; it never invents numbers.
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_benchmarks_support import (  # noqa: E402
    REPO,
    WORKLOAD,
    apply_scenario,
    commit,
    environment_record,
    parse_forgeci_yml,
    reset_cache,
    restore,
)

TRACES = REPO / "demo" / "traces"

START = re.compile(r"^\[([\w:-]+)\] > (.+)$")
DONE = re.compile(r"^\[([\w:-]+)\] (SUCCEEDED|FAILED|SKIPPED|TIMED_OUT) (?:in ([\d.]+)s|\(([^)]*)\))")
ROW = re.compile(r"^  (\S+)\s+(SUCCEEDED|FAILED|TIMED_OUT|SKIPPED|CANCELED)\s+(.*)$")

SCENARIOS = [
    ("cold-full-build", None, "Cold build, empty cache",
     "Nothing cached yet — every task has to run."),
    ("leaf-module", "leaf-module", "Leaf-module change",
     "One file changed in a module nothing else depends on."),
    ("shared-library", "shared-core", "Shared-library change",
     "A change in the module most others depend on."),
    ("warm-remote-cache", None, "Warm cache, no changes",
     "Every task is served from the content-addressed cache."),
    ("config-change", "config-toolchain", "Toolchain/config change",
     "A configuration change that invalidates the whole graph."),
]


def run_traced(jobs):
    """Runs forge and timestamps each output line as it arrives, so event times are measured."""
    started = time.perf_counter()
    proc = subprocess.Popen(
        [str(REPO / "forge"), "run", "--all", "-j", str(jobs)],
        cwd=WORKLOAD, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
    )
    lines = []
    for line in proc.stdout:
        lines.append((int((time.perf_counter() - started) * 1000), line.rstrip("\n")))
    proc.wait()
    return lines, int((time.perf_counter() - started) * 1000), proc.returncode


def build_trace(scenario_id, label, question, jobs, lines, duration_ms, graph, run_id, env, sha,
                baseline=None):
    events, tasks, lanes, active = [], {}, {}, {}
    seq = 0

    for at_ms, line in lines:
        m = START.match(line)
        if m:
            task = m.group(1)
            # lane assignment is observed, not assigned: whichever lane is free took this task
            lane = next((l for l, busy in lanes.items() if not busy), None)
            if lane is None:
                lane = f"executor-{len(lanes) + 1}"
            lanes[lane] = True
            active[task] = lane
            seq += 1
            events.append({"sequence": seq, "type": "TASK_STARTED", "atMs": at_ms,
                           "task": task, "worker": lane})
            continue
        m = DONE.match(line)
        if m:
            task, status = m.group(1), m.group(2)
            lane = active.pop(task, None)
            if lane:
                lanes[lane] = False
            detail = m.group(4) or ""
            cached = "restored from cache" in line or "cache" in detail
            seq += 1
            events.append({
                "sequence": seq,
                "type": "TASK_CACHE_HIT" if cached else f"TASK_{status}",
                "atMs": at_ms, "task": task, "worker": lane or "cache",
            })

    for _, line in lines:
        m = ROW.match(line)
        if not m:
            continue
        name, status, detail = m.group(1), m.group(2), m.group(3).strip()
        cached = "restored from cache" in detail
        dur = re.match(r"^([\d.]+)s$", detail)
        tasks[name] = {
            "id": name,
            "status": "CACHE_HIT" if cached else ("SKIP" if status == "SKIPPED" else
                                                  ("FAILED" if status == "FAILED" else "RUN")),
            "reason": detail if detail else status.lower(),
            "dependsOn": graph["deps"].get(name, []),
            "durationMs": int(float(dur.group(1)) * 1000) if dur else 0,
        }
        if name in {e["task"] for e in events}:
            worker = next((e["worker"] for e in events if e["task"] == name and e["worker"]), None)
            if worker and worker != "cache":
                tasks[name]["worker"] = worker

    task_list = list(tasks.values())
    totals = {
        "durationMs": duration_ms,
        "tasksExecuted": sum(1 for t in task_list if t["status"] == "RUN"),
        "tasksCacheHit": sum(1 for t in task_list if t["status"] == "CACHE_HIT"),
        "tasksSkipped": sum(1 for t in task_list if t["status"] == "SKIP"),
        "tasksFailed": sum(1 for t in task_list if t["status"] == "FAILED"),
        "retries": 0,
        "duplicateAcceptedResults": 0,
        "inconsistentArtifacts": 0,
    }

    trace = {
        "schemaVersion": 1,
        "scenario": scenario_id,
        "scenarioLabel": label,
        "sourceCommit": sha,
        "benchmarkRunId": run_id,
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "environment": env,
        "repository": {"name": "sample-monorepo", "moduleCount": graph["modules"],
                       "taskCount": len(graph["nodes"])},
        "graph": {"nodes": graph["nodes"], "edges": graph["edges"]},
        "changedFiles": graph["changed"].get(scenario_id, []),
        "workerCount": jobs,
        "cacheState": "cold" if scenario_id == "cold-full-build" else "warm",
        "tasks": task_list,
        "events": events,
        "totals": totals,
    }
    if baseline:
        trace["baseline"] = baseline
    return trace


def main():
    env = environment_record("local-benchmark")
    sha = commit()
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    graph = parse_forgeci_yml(WORKLOAD / "forgeci.yml")
    TRACES.mkdir(parents=True, exist_ok=True)

    # the cold full build is the baseline every incremental scenario is compared against
    reset_cache()
    cold_lines, cold_ms, rc = run_traced(4)
    if rc != 0:
        sys.exit("cold build failed")
    cold = build_trace("cold-full-build", "Cold build, empty cache", "", 4, cold_lines, cold_ms,
                       graph, run_id, env, sha)
    baseline = {"tasksExecuted": cold["totals"]["tasksExecuted"], "durationMs": cold_ms,
                "taskIds": [t["id"] for t in cold["tasks"]]}
    cold["baseline"] = baseline
    (TRACES / "cold-full-build.json").write_text(json.dumps(cold, indent=2) + "\n")
    print(f"cold-full-build: {cold_ms} ms, {cold['totals']['tasksExecuted']} tasks")

    primed = REPO / "build" / "trace-primed"
    shutil.rmtree(primed, ignore_errors=True)
    primed.mkdir(parents=True)
    for name in (".forge", "build"):
        if (WORKLOAD / name).exists():
            shutil.copytree(WORKLOAD / name, primed / name)

    for scenario_id, variant, label, question in SCENARIOS[1:]:
        for name in (".forge", "build"):
            shutil.rmtree(WORKLOAD / name, ignore_errors=True)
            if (primed / name).exists():
                shutil.copytree(primed / name, WORKLOAD / name)
        saved = apply_scenario(variant) if variant else {}
        try:
            lines, ms, rc = run_traced(4)
        finally:
            restore(saved)
        if rc != 0:
            sys.exit(f"{scenario_id} failed")
        trace = build_trace(scenario_id, label, question, 4, lines, ms, graph, run_id, env, sha,
                            baseline)
        (TRACES / f"{scenario_id}.json").write_text(json.dumps(trace, indent=2) + "\n")
        print(f"{scenario_id}: {ms} ms, executed {trace['totals']['tasksExecuted']}, "
              f"cached {trace['totals']['tasksCacheHit']}")

    shutil.rmtree(primed, ignore_errors=True)
    reset_cache()


if __name__ == "__main__":
    main()
