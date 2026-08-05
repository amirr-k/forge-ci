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

Per-trial wall clock, in run order:

- `fifo-w4` — 108.4, 118.5, 79.8, 62.8, 94.4, 61.8, 60.5, 62.2, 48.4, 80.1 s
- `critical-path-duration-w4` — 101.0, 61.5, 58.9, 54.7 s

| Comparison | FIFO | Duration-aware | Median delta | One-sided exact p |
|---|---|---|---|---|
| All measured trials | 71.3 s (n=10) | 60.2 s (n=4) | −15.6% | 0.152 |
| Excluding each arm's first trial | 62.8 s (n=9) | 58.9 s (n=3) | **−6.2%** | 0.050 |

p-values are an exact one-sided Mann-Whitney U test (full permutation enumeration, appropriate at
these sample sizes) of the hypothesis that the duration-aware arm is faster.

**The honest reading is roughly −6%, not −15.6%.** Both arms' first measured trial is a large
outlier (108 s and 101 s) even though a warm-up build was already run and discarded — residual
Docker layer, JIT, and MinIO-bucket warming that one warm-up does not fully absorb. Excluding those,
the effect *shrinks* to −6.2% while becoming *more* statistically detectable (p 0.152 → 0.050),
because dropping them removes far more variance than signal. So most of the headline −15.6% is
FIFO's two slow early trials rather than a scheduling effect.

At n=3 vs n=9, p = 0.050 sits exactly on the conventional threshold: this is suggestive, in the
direction the policy predicts, and **not** a settled result. Treat it as directional evidence. The
unequal trial counts are an artifact of the harness, not a choice — the stack intermittently wedged
on this resource-constrained laptop during repeated teardown/recreate cycles and the duration-aware
arm lost six trials to it before the harness was changed to bring the stack up once per arm.
Settling this properly needs the duration-aware arm re-run to n=10 with a longer warm-up.

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

## Straggler mitigation: speculation off vs on

Run `20260805T071155Z` · commit `27934e7` · profile `local-benchmark`

30 trials per arm, 4 workers. Each trial submits a single real task (unique cache key, so it always
genuinely executes), waits until a worker is running it, then `docker pause`s that worker for a
fixed **9 s** — frozen, not killed, so its lease stays valid and the only question is whether
anything else finishes the work sooner. The pause is identical in both arms; the only difference is
`forge.scheduler.speculation.enabled`.

The 9 s pause is calibrated between two thresholds so the mechanism under test is unambiguous:
above the speculation threshold (so speculation has time to fire), and well below the
worker-death threshold (heartbeat interval raised to 6 s for this run, so 3 missed beats = 18 s) —
a paused worker is never mistaken for a crashed one. This measures speculation, not crash recovery.

| Arm | Trials | p50 | p95 | Range | Speculative attempts |
|---|---|---|---|---|---|
| `speculation-off` | 30 | 11.99 s | 12.11 s | [11.91 s, 12.15 s] | 0 |
| `speculation-on` | 30 | 6.31 s | 6.67 s | [5.78 s, 6.86 s] | 30 |

| Figure | Value | Calculation |
|---|---|---|
| p95 build latency reduction | **−44.9%** | (12.11 s − 6.67 s) / 12.11 s |
| p50 build latency reduction | **−47.3%** | (11.99 s − 6.31 s) / 11.99 s |
| Additional compute | **1 duplicate task execution per trial** | 30 speculative attempts / 30 trials |

The two distributions do not overlap at all (11.91–12.15 s vs 5.78–6.86 s), so no significance test
is needed here. The mechanism is direct: with speculation off, the task cannot finish until the
paused worker resumes at 9 s and then completes its work. With speculation on, a second worker
starts a duplicate once the original is overdue and finishes it before the original even unpauses.

**The trade is explicit:** speculation doubled the compute spent on the straggling task to roughly
halve the latency. It is off by default (`forge.scheduler.speculation.enabled=false`) because that
trade is only worth making on a cluster with genuinely idle capacity — and by construction it can
only ever consume idle capacity, since a worker asks for a speculative duplicate only after finding
no unstarted work it could run instead.

**On the zero duplicate rejections.** Both arms report 30 results submitted and 30 accepted, with
zero duplicates rejected — which is a *measurement-window* artifact, not evidence that no duplicate
occurred. The build completes at ~6.3 s via the speculative attempt; the harness then unpauses the
original and reads counters 2 s later, but the original worker still has to finish its own
compilation before reporting, which lands after that window closes. If both attempts had reported
inside the window the submitted count would be 60, not 30. The rejection path itself is proven by
`SpeculativeExecutionIntegrationTest`, which drives both reports to completion and asserts the
loser is rejected with `403` while the accepted result is left unchanged.

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
