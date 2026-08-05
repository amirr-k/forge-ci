#!/usr/bin/env python3
"""Renders the Docker-stack benchmark, fault-injection, speculation, and remote-cache evidence
into Markdown sections for docs/benchmarks.md.

Reads the checkpoint/result JSON each harness writes and computes every figure from the raw
records -- nothing is transcribed by hand, so a stale number in the docs implies a stale raw file
behind it, never the reverse.

Usage:
    python3 benchmarks/scripts/write-docker-report.py
"""
from __future__ import annotations

import json
import statistics
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RAW = REPO / "benchmarks" / "results" / "raw"


def load(name: str) -> dict | None:
    path = RAW / f"{name}-checkpoint.json"
    return json.loads(path.read_text()) if path.exists() else None


def scheduling_section(payload: dict) -> str:
    env = payload["environment"]
    arms = payload["arms"]
    task_count = next(iter(arms.values()))["trials"][0]["task_count"] if arms else "?"
    lines = [
        "## Docker-worker scheduler comparison",
        "",
        f"Run `{payload['started_at']}` · commit `{env['commit'][:7]}` · profile `{env['profile']}`",
        "",
        "FIFO vs duration-aware critical-path scheduling, both at 4 Docker workers with cleared",
        "caches between every trial. Every task is claimed over HTTP by a worker container and",
        "executed in its own sandbox container against the full Kafka/Redis/MySQL/MinIO stack --",
        "distinct from the `forge run -j N` local-executor numbers above, and never compared",
        "directly to them.",
        "",
        f"- Workload: `demo/scale-monorepo` — 50 modules, 5 dependency layers, **{task_count} tasks**.",
        "- One task per worker (`FORGE_WORKER_MAX_CONCURRENCY=1`).",
        f"- Hardware: {env['platform']}, {env['cpu_count']} cores.",
        "- One discarded warm-up per arm, then the measured trials below; median and full range",
        "  reported, no trial dropped after the fact.",
        "",
        "| Arm | Policy | Trials | Median | Range |",
        "|---|---|---|---|---|",
    ]
    for arm_id, arm in arms.items():
        s = arm.get("summary", summarise_wall(arm["trials"]))
        lines.append(
            f"| `{arm_id}` | {arm.get('policy', '?')} | {s['trials']} | "
            f"{fmt_s(s['median_ms'])} | [{fmt_s(s['min_ms'])}, {fmt_s(s['max_ms'])}] |"
        )

    fifo = arms.get("fifo-w4", {}).get("summary")
    cpd = arms.get("critical-path-duration-w4", {}).get("summary")
    if fifo and cpd and fifo.get("median_ms") and cpd.get("median_ms"):
        delta = (fifo["median_ms"] - cpd["median_ms"]) / fifo["median_ms"] * 100
        lines += [
            "",
            "### Derived figure",
            "",
            "| Figure | Value | Calculation |",
            "|---|---|---|",
            f"| duration-aware critical-path vs FIFO, 4 Docker workers | **{delta:+.1f}%** | "
            f"({fmt_s(fifo['median_ms'])} − {fmt_s(cpd['median_ms'])}) / {fmt_s(fifo['median_ms'])}, medians |",
        ]
    return "\n".join(lines) + "\n"


def summarise_wall(trials):
    good = [t for t in trials if t.get("state") == "SUCCEEDED"]
    times = [t["wall_ms"] for t in good]
    return {
        "trials": len(trials),
        "median_ms": statistics.median(times) if times else None,
        "min_ms": min(times) if times else None,
        "max_ms": max(times) if times else None,
    }


def fmt_s(ms) -> str:
    return f"{ms/1000:.1f}s" if ms is not None else "n/a"


def faults_section(payload: dict) -> str:
    env = payload["environment"]
    s = payload["summary"]
    lines = [
        "## Worker-failure trials",
        "",
        f"Run `{payload['started_at']}` · commit `{env['commit'][:7]}` · profile `{env['profile']}`",
        "",
        f"{s['trials']} trials against the live Docker stack, {env['workers']} workers. Each trial",
        "submits a 12-task build, waits until a worker is genuinely executing one of its tasks, then",
        "`SIGKILL`s that container -- it never deregisters and never reports, so the control plane",
        "must notice via missed heartbeats and reclaim the attempt on its own.",
        "",
        "A trial counts as recovered only if the build still reaches `SUCCEEDED` **and** every",
        "succeeded task carries the artifact digest its winning attempt produced.",
        "",
        "| Metric | Value |",
        "|---|---|",
        f"| Trials | {s['trials']} |",
        f"| Recovered | {s['recovered']} |",
        f"| Recovery rate | **{s['recovery_rate']*100:.1f}%** |",
        f"| Artifact-correct recoveries | {s['artifact_correct']}/{s['recovered']} |",
        f"| Recovery latency p50 | **{fmt_s(s['recovery_p50_ms'])}** |",
        f"| Recovery latency p95 | **{fmt_s(s['recovery_p95_ms'])}** |",
        f"| Recovery latency max | {fmt_s(s['recovery_max_ms'])} |",
        f"| Results submitted by workers | {s['results_submitted']:.0f} |",
        f"| Results accepted | {s['results_accepted']:.0f} |",
        f"| Duplicate results rejected | {s['duplicates_rejected']:.0f} |",
        "",
        "`submitted` counts every result a worker sent; `accepted` counts the ones that won their",
        "task run. ForgeCI never claims exactly-once execution, only that exactly one result is ever",
        "accepted per task run.",
    ]
    return "\n".join(lines) + "\n"


def speculation_section(payload: dict) -> str:
    env = payload["environment"]
    off = payload["arms"].get("speculation-off", {}).get("summary", {})
    on = payload["arms"].get("speculation-on", {}).get("summary", {})
    lines = [
        "## Straggler mitigation: speculation on vs off",
        "",
        f"Run `{payload['started_at']}` · commit `{env['commit'][:7]}` · profile `{env['profile']}`",
        "",
        f"Each trial runs one real task and freezes (`docker pause`) the worker executing it for a",
        f"fixed {env['stall_seconds']}s -- identical in both arms, calibrated above the speculation",
        "threshold and well below the worker-death threshold, so this measures speculation and not",
        "crash recovery. Only `forge.scheduler.speculation.enabled` differs between arms.",
        "",
        "| Arm | Trials | Recovered | p50 build latency | p95 build latency | Speculative attempts | Duplicates rejected |",
        "|---|---|---|---|---|---|---|",
    ]
    for name, s in (("speculation-off", off), ("speculation-on", on)):
        lines.append(
            f"| `{name}` | {s.get('trials', 0)} | {s.get('recovered', 0)} | "
            f"{fmt_s(s.get('build_latency_p50_ms'))} | {fmt_s(s.get('build_latency_p95_ms'))} | "
            f"{s.get('speculative_attempts_started', 0):.0f} | {s.get('duplicates_rejected', 0):.0f} |"
        )
    if off.get("build_latency_p95_ms") and on.get("build_latency_p95_ms"):
        delta = (off["build_latency_p95_ms"] - on["build_latency_p95_ms"]) / off["build_latency_p95_ms"] * 100
        lines += [
            "",
            "### Derived figure",
            "",
            "| Figure | Value | Calculation |",
            "|---|---|---|",
            f"| p95 build latency reduction from speculation | **{delta:+.1f}%** | "
            f"({fmt_s(off['build_latency_p95_ms'])} − {fmt_s(on['build_latency_p95_ms'])}) / "
            f"{fmt_s(off['build_latency_p95_ms'])} |",
            "",
            f"Additional compute: speculation started {on.get('speculative_attempts_started', 0):.0f} "
            f"extra attempts across {on.get('trials', 0)} trials to buy that latency reduction, of "
            f"which {on.get('duplicates_rejected', 0):.0f} results were discarded as duplicates ("
            "the losing side of the race, not an error).",
        ]
    return "\n".join(lines) + "\n"


def remote_cache_section(payload: dict) -> str:
    env = payload["environment"]
    lines = [
        "## Remote content-addressed cache verification",
        "",
        f"Run `{payload['started_at']}` · commit `{env['commit'][:7]}` · profile `{env['profile']}`",
        "",
        f"Build 1 executes {payload['task_count']} tasks cold, populating MinIO. Both worker",
        "containers are then destroyed along with their workspace volume, and build 2 is submitted",
        "with the identical cache keys against fresh workers with no local state whatsoever.",
        "",
        "| Check | Result |",
        "|---|---|",
        f"| Build 1: all tasks succeeded | {payload['build_1_all_succeeded']} |",
        f"| Build 1 workers used | {payload['build_1_workers_used']} |",
        f"| Build 2: every task resolved `CACHED` | {payload['build_2_all_cached']} |",
        f"| Build 2: no worker claimed anything | {payload['build_2_no_worker_claimed']} |",
        f"| Build 2 digests match build 1 | {payload['build_2_digests_match_build_1']} |",
        f"| Independently re-hashed bytes match | {payload['independent_rehash_all_match']} |",
        f"| **Overall** | **{'PASSED' if payload['passed'] else 'FAILED'}** |",
        "",
        "The re-hash step re-downloads each artifact through the public",
        "`/api/artifacts/lookup` endpoint and computes SHA-256 over the bytes in Python, independent",
        "of the digest column in MySQL -- this verifies restored *content*, not just a database row.",
    ]
    return "\n".join(lines) + "\n"


def main() -> int:
    sched = load("docker-scheduling")
    faults = load("fault-trials")
    speculation = load("speculation-trials")
    remote_cache_files = sorted(RAW.glob("*-docker-remote-cache.json"))

    out = []
    if sched:
        out.append(scheduling_section(sched))
    if faults:
        out.append(faults_section(faults))
    if speculation:
        out.append(speculation_section(speculation))
    if remote_cache_files:
        out.append(remote_cache_section(json.loads(remote_cache_files[-1].read_text())))

    text = "\n".join(out)
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
