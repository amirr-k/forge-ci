# ForgeCI benchmark results

Run `20260803T184236Z` · commit `2a2f0d6` · profile `local-benchmark`

## How this was measured

- Command: `JAVA_HOME=<jdk21> python3 benchmarks/scripts/run-benchmarks.py --warmups 3 --trials 10`
- Workload: `demo/sample-monorepo` — 11-module Java monorepo; every task runs javac, verifies compiled classes by loading them, packages a jar, and hashes it (11 modules, 25 tasks).
- Hardware: Darwin 25.5.0 / arm64, 10 cores, 16.0 GB RAM.
- Java: 21.0.12. Build tool: forge 0.1.0-SNAPSHOT.
- Method: 3 warm-up runs discarded, 10 measured trials retained per
  scenario. every measured trial is retained; no cherry-picking.
- `jobs` is the CLI's concurrent-task limit (`forge run -j N`) on one machine. These are parallel
  local executors, **not** distributed Docker workers — the distributed path is validated
  separately and is not the source of these timings.

## Results

| Scenario | Jobs | Cache | Ran | Reused | Mean ms | Median ms | p95 ms | Stddev ms | Trials |
|---|---|---|---|---|---|---|---|---|---|
| `cold-full-build-j1` | 1 | cold | 25 | 0 | 7695 | 7601 | 8508 | 302 | 10 |
| `cold-full-build-j2` | 2 | cold | 25 | 0 | 4862 | 4867 | 4932 | 45 | 10 |
| `cold-full-build-j4` | 4 | cold | 25 | 0 | 3788 | 3769 | 4007 | 104 | 10 |
| `warm-no-change` | 4 | warm | 0 | 25 | 264 | 260 | 301 | 20 | 10 |
| `leaf-module` | 4 | warm | 2 | 23 | 996 | 972 | 1156 | 64 | 10 |
| `shared-library` | 4 | warm | 22 | 3 | 3862 | 3738 | 5033 | 434 | 10 |
| `config-change` | 4 | warm | 25 | 0 | 3943 | 3931 | 4043 | 59 | 10 |

## Derived figures

All computed from the medians above.

| Figure | Value | Calculation |
|---|---|---|
| Incremental build reduction (leaf-module) | **74.2%** | (cold-j4 3769 ms − leaf 972 ms) / 3769 ms |
| Incremental speedup (leaf-module) | **3.88×** | 3769 ms / 972 ms |
| 1 → 2 executor speedup (cold build) | **1.56×** | 7601 ms / 4867 ms |
| 1 → 4 executor speedup (cold build) | **2.02×** | 7601 ms / 3769 ms |
| Tasks executed vs reused (leaf-module) | **2 ran, 23 reused** | of 25 total |
| Warm cache, no changes | **260 ms** | all 25 tasks restored |

## Where ForgeCI does not help

Reported because omitting them would misrepresent the system:

- **Shared-library change** — median 3738 ms against a
  3769 ms cold build. A change in the module most others depend on invalidates most of
  the graph, so incremental selection saves little.
- **Toolchain/config change** — median 3931 ms. `toolchain.lock` is
  an input to every task, so every task is invalidated and the build is a full rebuild by design.
- Adding executors past the graph's critical path stops helping: 1 → 2 gives 1.56× but
  2 → 4 only gives a further 1.29×,
  because the dependency chain, not CPU, is the limit.

## Raw evidence

- `benchmarks/results/latest.json` — this run, every trial retained.
- `benchmarks/results/raw/20260803T184236Z.json` — same payload, archived by run id.
- Per-trial durations are in each scenario's `stats.samples_ms`.

## Honest limitations

- These are `local-benchmark` profile results on one developer machine under normal desktop load,
  not an isolated benchmark host. Variance is visible in the stddev column.
- The AWS reference profile was not exercised for this run, so no result here is an AWS result.

## Reproducing

```bash
export JAVA_HOME=<a JDK 21+ install>
python3 benchmarks/scripts/run-benchmarks.py --warmups 3 --trials 10
python3 benchmarks/scripts/write-report.py
```

Both files in `benchmarks/results/` are regenerated from the run you just did.
