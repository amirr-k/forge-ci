# ForgeCI benchmark results

Run `20260805T042748Z` · commit `aab36f6` · profile `local-benchmark`

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
| `cold-full-build-j1` | 1 | cold | 25 | 0 | 7503 | 7496 | 7560 | 38 | 10 |
| `cold-full-build-j2` | 2 | cold | 25 | 0 | 4933 | 4928 | 5020 | 52 | 10 |
| `cold-full-build-j4` | 4 | cold | 25 | 0 | 4332 | 4356 | 4526 | 134 | 10 |
| `warm-no-change` | 4 | warm | 0 | 25 | 269 | 270 | 275 | 5 | 10 |
| `leaf-module` | 4 | warm | 2 | 23 | 1013 | 1010 | 1084 | 28 | 10 |
| `shared-library` | 4 | warm | 22 | 3 | 4428 | 4401 | 4616 | 77 | 10 |
| `config-change` | 4 | warm | 25 | 0 | 4854 | 4834 | 4922 | 42 | 10 |

## Derived figures

All computed from the medians above.

| Figure | Value | Calculation |
|---|---|---|
| Incremental build reduction (leaf-module) | **76.8%** | (cold-j4 4356 ms − leaf 1010 ms) / 4356 ms |
| Incremental speedup (leaf-module) | **4.31×** | 4356 ms / 1010 ms |
| 1 → 2 executor speedup (cold build) | **1.52×** | 7496 ms / 4928 ms |
| 1 → 4 executor speedup (cold build) | **1.72×** | 7496 ms / 4356 ms |
| Tasks executed vs reused (leaf-module) | **2 ran, 23 reused** | of 25 total |
| Warm cache, no changes | **270 ms** | all 25 tasks restored |

## Where ForgeCI does not help — and where it costs

Reported because omitting them would misrepresent the system:

- **Shared-library change** — median 4401 ms against a
  4356 ms cold build, i.e. +45 ms. With a
  stddev of 77 ms that difference is inside the noise: a change to
  the module most others depend on invalidates most of the graph, so incremental selection buys
  nothing measurable. It is not slower, it is simply no better.
- **Toolchain/config change — genuinely slower than a plain full build**, by
  +478 ms
  (+11.0%): median
  4834 ms vs 4356 ms. `toolchain.lock` is a declared input
  to every task, so every cache key changes. ForgeCI then hashes 25 sets of
  inputs, looks up 25 keys, misses all of them, runs the full build anyway,
  and writes 25 new entries into an already-populated store — bookkeeping
  with zero reuse to amortize it. Isolating the starting state shows the cost comes from the
  populated cache store (+1.4% with the cache primed and outputs cleared), not from stale build
  outputs (−2.6% with outputs primed and the cache cleared).

  This is the standard trade every caching build system makes, and it is bounded: a few percent on
  the change that invalidates everything, against
  4.3× on the ordinary single-module change. It is also amplified by this
  workload's small tasks (~174 ms each) — the overhead is
  roughly fixed per task, so it shrinks as a share of longer real-world tasks.
- Adding executors past the graph's critical path stops helping: 1 → 2 gives 1.52× but
  2 → 4 only gives a further 1.13×,
  because the dependency chain, not CPU, is the limit.

## Raw evidence

- `benchmarks/results/latest.json` — this run, every trial retained.
- `benchmarks/results/raw/20260805T042748Z.json` — same payload, archived by run id.
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

---

# Distributed Docker-worker benchmarks

Everything above measures `forge run -j N` — concurrent **local executors** in one JVM. Everything
below measures the real `deploy/compose.yaml` stack: MySQL, Kafka, Redis, MinIO, the Spring Boot
control plane, and Docker worker containers that claim tasks over HTTP and execute each one in its
own sandbox container. The two sets of numbers are not comparable and are never combined.

## Docker-worker scheduler comparison

Run `20260805T062402Z` · commit `ac454c9` · profile `local-benchmark`

FIFO vs duration-aware critical-path, both at 4 Docker workers, same graph, caches cleared between
arms and a unique cache-key revision per trial so every trial genuinely executes.

- Workload: `demo/scale-monorepo` — 50 modules, 5 dependency layers, **150 tasks**, 410 Java sources.
- `FORGE_WORKER_MAX_CONCURRENCY=1`, so *N workers* means exactly N tasks may run at once.
- Hardware: macOS 26.5.2 / arm64, 10 cores, 16 GB RAM.
- One discarded warm-up per arm; every measured trial retained.

| Arm | Policy | Trials | Median | Range |
|---|---|---|---|---|
| `fifo-w4` | fifo | 10 | 71.3 s | [48.4 s, 118.5 s] |
| `critical-path-duration-w4` | critical-path-duration | 4 | 60.2 s | [54.7 s, 101.0 s] |

| Figure | Value | Calculation |
|---|---|---|
| duration-aware critical-path vs FIFO | **−15.6%** | (71.3 s − 60.2 s) / 71.3 s, medians |

**Read this one cautiously.** The arms have unequal trial counts (10 vs 4) and both ranges are
wide — 48–119 s and 55–101 s overlap substantially. The medians differ in the direction the
critical-path policy predicts, but on this hardware, at these sample sizes, the difference is not
separated from run-to-run variance. It is reported as directional evidence, not a proven speedup.
The unequal counts are themselves an artifact: the stack intermittently wedged on this
resource-constrained laptop after repeated full teardown/recreate cycles, and the duration-aware
arm lost six trials to that before the harness was made to bring the stack up once per arm.

## Worker-failure trials

Run `20260805T041424Z` · commit `aab36f6` · profile `local-benchmark`

50 trials against the live stack with 4 workers. Each trial submits a 12-task build, waits until a
worker is genuinely executing one of its tasks, then `SIGKILL`s that container — it never
deregisters and never reports, so the control plane must notice by missed heartbeats and reclaim
the attempt on its own. A trial counts as recovered only if the build still reaches `SUCCEEDED`
**and** every succeeded task carries the artifact digest its winning attempt produced.

| Metric | Value |
|---|---|
| Trials | 50 |
| Recovered | 50 |
| Recovery rate | **100%** |
| Artifact-correct recoveries | 50 / 50 |
| Recovery latency p50 | **10.4 s** |
| Recovery latency p95 | **11.9 s** |
| Recovery latency max | 12.5 s |
| Results submitted by workers | 602 |
| Results accepted | 602 |
| Duplicate results rejected | 0 |

Recovery latency is measured from the instant the `SIGKILL` lands to the instant the build reaches
`SUCCEEDED`, so it includes detection, reclamation, reassignment, and re-execution of the lost task.

**On the zero duplicates.** `submitted == accepted` and zero rejections here is not evidence that
duplicate suppression works — it is evidence that *this* fault never produces a duplicate to
suppress. A `SIGKILL`ed worker never reports at all, so only one result per task run is ever
submitted. The path where two attempts really do race (a stalled worker resuming after its task was
already completed elsewhere) is exercised by `SpeculativeExecutionIntegrationTest`, which asserts
the second reporter is rejected with `403` and the accepted result is unchanged.

## Reproducing the distributed benchmarks

```bash
# scheduler comparison (FIFO vs duration-aware critical path, 150-task graph, 4 workers)
python3 benchmarks/scripts/run-docker-benchmarks.py --trials 10

# worker-failure trials (SIGKILL a worker holding a live attempt)
python3 benchmarks/scripts/run-fault-trials.py --trials 50

# straggler mitigation (speculation off vs on under an identical deterministic pause)
python3 benchmarks/scripts/run-speculation-trials.py --trials 30

# remote content-addressed cache restore + independent checksum verification
python3 benchmarks/scripts/run-remote-cache-check.py
```

Each harness writes raw per-trial JSON to `benchmarks/results/raw/` after **every** completed trial
and skips work already present on re-invocation, so an interrupted run resumes instead of starting
over. All four bring the compose stack up and tear it down themselves.
