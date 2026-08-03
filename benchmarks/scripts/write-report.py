#!/usr/bin/env python3
"""Renders benchmarks/results/latest.json into latest.md and docs/benchmarks.md.

Every published figure is computed here from the committed raw trials — nothing is typed by hand,
so a number in the docs cannot drift from the evidence behind it.
"""
from __future__ import annotations

import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RESULTS = REPO / "benchmarks" / "results"
DOCS = REPO / "docs"

COMMAND = "JAVA_HOME=<jdk21> python3 benchmarks/scripts/run-benchmarks.py --warmups 3 --trials 10"


def find(scenarios, sid):
    return next((s for s in scenarios if s["id"] == sid), None)


def row(s):
    st = s["stats"]
    final = s.get("final") or {}
    tasks = final.get("tasks", [])
    ran = sum(1 for t in tasks if t["status"] == "RUN")
    cached = sum(1 for t in tasks if t["status"] == "CACHE_HIT")
    return (
        f"| `{s['id']}` | {s['jobs']} | {s['cacheState']} | {ran} | {cached} | "
        f"{st['mean_ms']:.0f} | {st['median_ms']:.0f} | {st['p95_ms']:.0f} | "
        f"{st['stddev_ms']:.0f} | {st['trials']} |"
    )


def main():
    data = json.loads((RESULTS / "latest.json").read_text())
    s = data["scenarios"]
    env, method, workload = data["environment"], data["method"], data["workload"]

    cold1, cold2, cold4 = find(s, "cold-full-build-j1"), find(s, "cold-full-build-j2"), find(s, "cold-full-build-j4")
    leaf, shared, config = find(s, "leaf-module"), find(s, "shared-library"), find(s, "config-change")
    warm = find(s, "warm-no-change")

    base_ms = cold4["stats"]["median_ms"]
    leaf_ms = leaf["stats"]["median_ms"]
    reduction = (base_ms - leaf_ms) / base_ms * 100
    speedup_leaf = base_ms / leaf_ms
    sp2 = cold1["stats"]["median_ms"] / cold2["stats"]["median_ms"]
    sp4 = cold1["stats"]["median_ms"] / cold4["stats"]["median_ms"]

    leaf_tasks = leaf.get("final", {}).get("tasks", [])
    leaf_ran = sum(1 for t in leaf_tasks if t["status"] == "RUN")
    leaf_cached = sum(1 for t in leaf_tasks if t["status"] == "CACHE_HIT")

    header = f"""# ForgeCI benchmark results

Run `{data['benchmarkRunId']}` · commit `{data['commit']}` · profile `{env['profile']}`

## How this was measured

- Command: `{COMMAND}`
- Workload: `{workload['repository']}` — {workload['description']} ({workload['moduleCount']} modules, {workload['taskCount']} tasks).
- Hardware: {env['os']} / {env['arch']}, {env['cpuCores']} cores, {env['memoryGb']} GB RAM.
- Java: {env['javaVersion']}. Build tool: {env['buildToolVersion']}.
- Method: {method['warmups']} warm-up runs discarded, {method['trials']} measured trials retained per
  scenario. {method['note']}.
- `jobs` is the CLI's concurrent-task limit (`forge run -j N`) on one machine. These are parallel
  local executors, **not** distributed Docker workers — the distributed path is validated
  separately and is not the source of these timings.

## Results

| Scenario | Jobs | Cache | Ran | Reused | Mean ms | Median ms | p95 ms | Stddev ms | Trials |
|---|---|---|---|---|---|---|---|---|---|
"""
    body = "\n".join(row(x) for x in s if x) + "\n"

    derived = f"""
## Derived figures

All computed from the medians above.

| Figure | Value | Calculation |
|---|---|---|
| Incremental build reduction (leaf-module) | **{reduction:.1f}%** | (cold-j4 {base_ms:.0f} ms − leaf {leaf_ms:.0f} ms) / {base_ms:.0f} ms |
| Incremental speedup (leaf-module) | **{speedup_leaf:.2f}×** | {base_ms:.0f} ms / {leaf_ms:.0f} ms |
| 1 → 2 executor speedup (cold build) | **{sp2:.2f}×** | {cold1['stats']['median_ms']:.0f} ms / {cold2['stats']['median_ms']:.0f} ms |
| 1 → 4 executor speedup (cold build) | **{sp4:.2f}×** | {cold1['stats']['median_ms']:.0f} ms / {cold4['stats']['median_ms']:.0f} ms |
| Tasks executed vs reused (leaf-module) | **{leaf_ran} ran, {leaf_cached} reused** | of {workload['taskCount']} total |
| Warm cache, no changes | **{warm['stats']['median_ms']:.0f} ms** | all {workload['taskCount']} tasks restored |

## Where ForgeCI does not help

Reported because omitting them would misrepresent the system:

- **Shared-library change** — median {shared['stats']['median_ms']:.0f} ms against a
  {base_ms:.0f} ms cold build. A change in the module most others depend on invalidates most of
  the graph, so incremental selection saves little.
- **Toolchain/config change** — median {config['stats']['median_ms']:.0f} ms. `toolchain.lock` is
  an input to every task, so every task is invalidated and the build is a full rebuild by design.
- Adding executors past the graph's critical path stops helping: 1 → 2 gives {sp2:.2f}× but
  2 → 4 only gives a further {cold2['stats']['median_ms'] / cold4['stats']['median_ms']:.2f}×,
  because the dependency chain, not CPU, is the limit.

## Raw evidence

- `benchmarks/results/latest.json` — this run, every trial retained.
- `benchmarks/results/raw/{data['benchmarkRunId']}.json` — same payload, archived by run id.
- Per-trial durations are in each scenario's `stats.samples_ms`.

## Honest limitations

- These are `local-benchmark` profile results on one developer machine under normal desktop load,
  not an isolated benchmark host. Variance is visible in the stddev column.
- The AWS reference profile was not exercised for this run, so no result here is an AWS result.
"""

    (RESULTS / "latest.md").write_text(header + body + derived)

    DOCS.mkdir(exist_ok=True)
    (DOCS / "benchmarks.md").write_text(
        header + body + derived +
        "\n## Reproducing\n\n```bash\n"
        "export JAVA_HOME=<a JDK 21+ install>\n"
        "python3 benchmarks/scripts/run-benchmarks.py --warmups 3 --trials 10\n"
        "python3 benchmarks/scripts/write-report.py\n"
        "```\n\nBoth files in `benchmarks/results/` are regenerated from the run you just did.\n"
    )
    print("wrote benchmarks/results/latest.md and docs/benchmarks.md")
    print(f"  incremental reduction {reduction:.1f}%, 1->4 speedup {sp4:.2f}x")


if __name__ == "__main__":
    main()
