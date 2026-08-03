#!/usr/bin/env python3
"""Runs the ForgeCI benchmark suite and writes raw per-trial evidence.

Every number this produces comes from a real `forge run` over the bundled sample monorepo, whose
tasks do actual javac compilation, class-loading verification, jar packaging, and content hashing.
No scenario sleeps, and no trial is discarded after the fact.

Usage:
    python3 benchmarks/scripts/run-benchmarks.py [--warmups N] [--trials N] [--profile NAME]
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKLOAD = REPO / "demo" / "sample-monorepo"
RAW = REPO / "benchmarks" / "results" / "raw"
RESULTS = REPO / "benchmarks" / "results"

ROW = re.compile(r"^  (\S+)\s+(SUCCEEDED|FAILED|TIMED_OUT|SKIPPED|CANCELED)\s+(.*)$")
SUMMARY = re.compile(r"^Run: (\d+) succeeded, (\d+) failed, (\d+) skipped in ([\d.]+)s$", re.M)


def sh(args, cwd, env=None):
    started = time.perf_counter()
    proc = subprocess.run(args, cwd=cwd, env=env, capture_output=True, text=True)
    return proc, (time.perf_counter() - started) * 1000.0


def parse_run(text):
    """Reads RunCommand's fixed-width rows — the same stable contract .github/scripts reads."""
    tasks = []
    for line in text.splitlines():
        m = ROW.match(line)
        if not m:
            continue
        name, status, detail = m.group(1), m.group(2), m.group(3).strip()
        cached = "restored from cache" in detail
        tasks.append(
            {
                "id": name,
                "status": "CACHE_HIT" if cached else ("SKIP" if status == "SKIPPED" else "RUN"),
                "reason": detail or status.lower(),
                "raw_status": status,
            }
        )
    m = SUMMARY.search(text)
    return {
        "tasks": tasks,
        "succeeded": int(m.group(1)) if m else 0,
        "failed": int(m.group(2)) if m else 0,
        "skipped": int(m.group(3)) if m else 0,
        "reported_seconds": float(m.group(4)) if m else 0.0,
    }


def reset_cache():
    shutil.rmtree(WORKLOAD / ".forge", ignore_errors=True)
    shutil.rmtree(WORKLOAD / "build", ignore_errors=True)


def apply_scenario(name):
    """Copies a scenario's variant sources over the workload; returns the files it touched."""
    src = WORKLOAD / "scenarios" / name
    if not src.is_dir():
        return {}
    saved = {}
    for path in src.rglob("*"):
        if not path.is_file():
            continue
        target = WORKLOAD / path.relative_to(src)
        saved[target] = target.read_bytes() if target.exists() else None
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    return saved


def restore(saved):
    for target, original in saved.items():
        if original is None:
            target.unlink(missing_ok=True)
        else:
            target.write_bytes(original)


def forge_run(jobs, env):
    return sh([str(REPO / "forge"), "run", "--all", "-j", str(jobs)], cwd=WORKLOAD, env=env)


def stats(samples):
    ordered = sorted(samples)
    return {
        "trials": len(samples),
        "mean_ms": round(statistics.fmean(samples), 2),
        "median_ms": round(statistics.median(samples), 2),
        "p95_ms": round(ordered[max(0, round(0.95 * len(ordered)) - 1)], 2),
        "stddev_ms": round(statistics.stdev(samples), 2) if len(samples) > 1 else 0.0,
        "min_ms": round(min(samples), 2),
        "max_ms": round(max(samples), 2),
        "samples_ms": [round(s, 2) for s in samples],
    }


def environment_record(profile):
    java = subprocess.run(
        [os.environ.get("JAVA_HOME", "") + "/bin/java", "-version"],
        capture_output=True,
        text=True,
    ).stderr.splitlines()
    mem_bytes = 0
    try:
        mem_bytes = int(subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True).stdout)
    except Exception:
        pass
    return {
        "profile": profile,
        "os": f"{platform.system()} {platform.release()}",
        "arch": platform.machine(),
        "cpuCores": os.cpu_count() or 1,
        "memoryGb": round(mem_bytes / (1024**3), 1) if mem_bytes else 0,
        "javaVersion": java[0].split('"')[1] if java and '"' in java[0] else "unknown",
        "buildToolVersion": "forge 0.1.0-SNAPSHOT",
    }


def commit():
    return subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"], cwd=REPO, capture_output=True, text=True
    ).stdout.strip()


def snapshot_cache(dest):
    """Copies the primed cache aside so every incremental trial starts from the same warm state."""
    shutil.rmtree(dest, ignore_errors=True)
    dest.mkdir(parents=True)
    for name in (".forge", "build"):
        src = WORKLOAD / name
        if src.exists():
            shutil.copytree(src, dest / name)


def restore_cache(src):
    for name in (".forge", "build"):
        shutil.rmtree(WORKLOAD / name, ignore_errors=True)
        if (src / name).exists():
            shutil.copytree(src / name, WORKLOAD / name)


def measure(label, jobs, cache, scenario, warmups, trials, env, primed=None):
    """Runs one timing scenario: warm-ups discarded, every measured trial kept.

    An incremental scenario has to start each trial from the same primed cache, otherwise trial 2
    onwards just re-reads the cache the previous trial already populated for the modified sources
    and reports a cache-hit time as if it were incremental compilation.
    """
    samples, last = [], None
    saved = {}
    try:
        for i in range(warmups + trials):
            if cache == "cold":
                reset_cache()
            elif primed is not None:
                restore(saved)
                saved = {}
                restore_cache(primed)
            if scenario and not saved:
                saved = apply_scenario(scenario)
            proc, elapsed = forge_run(jobs, env)
            if proc.returncode != 0:
                sys.stderr.write(f"\n{label}: forge run failed\n{proc.stdout[-3000:]}\n{proc.stderr[-2000:]}\n")
                raise SystemExit(1)
            parsed = parse_run(proc.stdout)
            last = parsed
            if i >= warmups:
                samples.append(elapsed)
            print(f"  {label}: {'warmup' if i < warmups else 'trial '} {i + 1}/{warmups + trials} "
                  f"{elapsed:8.1f} ms  run={sum(1 for t in parsed['tasks'] if t['status'] == 'RUN')} "
                  f"cache={sum(1 for t in parsed['tasks'] if t['status'] == 'CACHE_HIT')}", flush=True)
    finally:
        restore(saved)
    return stats(samples), last


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--warmups", type=int, default=3)
    ap.add_argument("--trials", type=int, default=10)
    ap.add_argument("--profile", default="local-benchmark")
    args = ap.parse_args()

    if not os.environ.get("JAVA_HOME"):
        sys.exit("JAVA_HOME must point at a JDK 21+ install")

    env = dict(os.environ)
    RAW.mkdir(parents=True, exist_ok=True)

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    env_record = environment_record(args.profile)
    sha = commit()

    print(f"benchmark run {run_id} on {env_record['os']} / {env_record['cpuCores']} cores, commit {sha}")
    print(f"workload: demo/sample-monorepo (real javac/jar/hash), warmups={args.warmups}, trials={args.trials}\n")

    scenarios = []

    # cold full build at each worker/job count — the scaling curve, all real compilation
    for jobs in (1, 2, 4):
        s, last = measure(f"cold-j{jobs}", jobs, "cold", None, args.warmups, args.trials, env)
        scenarios.append({"id": f"cold-full-build-j{jobs}", "jobs": jobs, "cacheState": "cold",
                          "stats": s, "final": last})

    # one primed cache built from the unmodified tree, reused as the starting point for every
    # warm scenario below so each trial measures the same transition
    primed = REPO / "build" / "benchmark-primed-cache"
    reset_cache()
    forge_run(4, env)
    snapshot_cache(primed)

    s, last = measure("warm-nochange", 4, "warm", None, args.warmups, args.trials, env, primed)
    scenarios.append({"id": "warm-no-change", "jobs": 4, "cacheState": "warm", "stats": s, "final": last})

    # incremental scenarios: primed cache with one variant applied
    for scenario, label in (("leaf-module", "leaf-module"), ("shared-core", "shared-library"),
                            ("config-toolchain", "config-change")):
        s, last = measure(label, 4, "warm", scenario, args.warmups, args.trials, env, primed)
        scenarios.append({"id": label, "jobs": 4, "cacheState": "warm", "stats": s, "final": last})

    shutil.rmtree(primed, ignore_errors=True)

    payload = {
        "benchmarkRunId": run_id,
        "commit": sha,
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "environment": env_record,
        "workload": {
            "repository": "demo/sample-monorepo",
            "description": "11-module Java monorepo; every task runs javac, verifies compiled "
                           "classes by loading them, packages a jar, and hashes it",
            "moduleCount": 11,
            "taskCount": 25,
        },
        "method": {
            "warmups": args.warmups,
            "trials": args.trials,
            "trialsDiscarded": 0,
            "note": "every measured trial is retained; no cherry-picking",
        },
        "scenarios": scenarios,
    }

    (RAW / f"{run_id}.json").write_text(json.dumps(payload, indent=2) + "\n")
    (RESULTS / "latest.json").write_text(json.dumps(payload, indent=2) + "\n")
    print(f"\nwrote benchmarks/results/raw/{run_id}.json and latest.json")


if __name__ == "__main__":
    main()
