# Resume block

Every figure below traces to `benchmarks/results/latest.md` (run `20260803T184236Z`,
commit `2a2f0d6`) or to the recorded distributed crash-recovery run in `docs/evidence.md`.

```text
ForgeCI — Distributed Incremental Build System | Java, Spring Boot, Kafka, Redis, MySQL, Docker | Demo | Source
• Cut incremental build time 74% (3.77s → 0.97s) on a 25-task Java monorepo by hashing task inputs and rebuilding only the 2 affected dependency-graph nodes, restoring the other 23 from a content-addressed cache.
• Scaled cold builds 2.0× (7.60s → 3.77s) from 1 to 4 concurrent executors by scheduling the task DAG along its critical path.
• Recovered a task orphaned by a killed worker in 13s using Redis lease expiry and durable retries, with a surviving worker completing it and no duplicate result accepted.
```

## Where each number comes from

| Claim | Source | Value |
|---|---|---|
| 74% incremental reduction | `latest.md` derived figures | (3769 − 972) / 3769 ms, medians of 10 trials |
| 2 ran / 23 reused of 25 | `latest.md` results table, `leaf-module` row | measured task statuses |
| 2.0× cold-build scaling | `latest.md` derived figures | 7601 ms (j1) / 3769 ms (j4), medians of 10 trials |
| 13s recovery | `docs/evidence.md` crash-recovery trace | `TASK_RUN_RETRY_WAIT` 18:27:49.066 → `TASK_RUN_SUCCEEDED` 18:28:02.376 |
| no duplicate result | same trace | 26 leases across 25 tasks, 25 successes; `search:build` leased twice, succeeded once |

## Rules applied

- Exactly three bullets, each about one rendered line.
- Only measured values; no rounding beyond what the trials support.
- Terraform is not listed. OCI is not mentioned. Replay mechanics are not described.
- No claim of public users, enterprise usage, or exactly-once execution — the third bullet claims
  idempotent acceptance ("no duplicate result accepted"), which is what the system actually
  guarantees.
- Skipped tasks and cache hits are distinguished: the leaf-module scenario **reused 23 from cache**
  rather than skipping them.
- The workload is named in bullet one, because "74% faster" is meaningless without it.

## Deliberately omitted, and why

- **AWS S3 is not in the technology line.** It is implemented and configuration-selectable, but the
  official AWS benchmark did not run this cycle (credentials were expired), so no published figure
  was produced against real S3. The resume rules only permit keeping AWS S3 if it was actually
  exercised in the AWS benchmark. Re-run phase 11 with valid credentials to earn it back.
- **"Docker workers" is not claimed for the scaling bullet.** The 2.0× figure was measured across
  concurrent local executors (`forge run -j N`). The distributed Docker-worker path is real and
  validated end to end — including the crash recovery in bullet three, which did run across two
  Docker workers — but the 1/2/4 scaling curve was not measured on it.
- **The shared-library and config-change scenarios are not cited.** They show ForgeCI saving little
  or nothing (a change to a widely depended-on module or to `toolchain.lock` invalidates most of
  the graph). They are reported in `docs/benchmarks.md` rather than hidden, but they do not belong
  in a highlight.
