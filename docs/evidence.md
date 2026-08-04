# Evidence

What was measured, on what, and what was not measured. Timing evidence lives in
[benchmarks.md](benchmarks.md); this file records the validation runs behind the
correctness and failure-recovery claims, and states the gaps honestly.

Host for every run below: Apple Silicon macOS (Darwin 25.5.0), 10 cores, 16 GB RAM,
JDK 21.0.12, Docker Desktop. Commit `2a2f0d6`.

## Test suite

`./gradlew check --rerun-tasks` — 1263 tests across 44 classes, 0 errors, 0 failures. Verified
additionally by three consecutive forced runs of the Testcontainers integration suite, all clean.

Two integration tests used to fail here. The cause was in the tests, not in the control plane, and
it is worth recording because the mechanism is easy to reintroduce.

The integration profile compresses `forge.worker.heartbeat-interval-ms` to 1 s so the
failure-recovery suite can observe lease expiry in seconds. `WorkerService` marks a worker
unhealthy after three missed intervals, so a worker that stops heartbeating is excluded from
claims after **3 s** and stays excluded until it heartbeats again. Two test classes registered a
worker and then polled `/claim` without ever heartbeating, so their own worker died mid-loop. The
reproducing run logged `worker worker-kafka-idempotent-… (35) marked unhealthy` — worker 35 being
exactly the worker named by the assertion that failed, `worker 35 never claimed solo:build`. This
is why enlarging the polling budgets never helped, and enlarging them further would have made it
worse: past 3 s the worker cannot be handed a task at any budget.

`BuildEventsIntegrationTest` had a second, independent bug. The claim queue is deliberately global
across every build in the system, and that test accepted whichever task run came back instead of
its own. In the reproducing run its `events:build` became READY at `16:46:39.140` and 26 ms later
the same worker leased `checkout:integration` — a leftover from an earlier test class — ran that
to SUCCEEDED, and never claimed its own task. Its build therefore never reached a terminal state,
and `GET /api/builds/{id}/events` only closes its emitter once the build is terminal, so the test
failed collecting the stream rather than at the real fault.

The fix routes claim polling through `ProtocolTestClient.claimOneOf`, which heartbeats on each
poll like a live worker and completes foreign backlog instead of mistaking it for its own, and
makes the scheduling tests finish the builds they start so they stop feeding leftovers into
unrelated classes a minute later. Nothing was disabled, excluded, retried, or given a longer
timeout.

One constraint that is load-bearing and not obvious from the diff: the two cache-reuse tests in
`WorkerSchedulingIntegrationTest` must upload artifacts with the cache key spelled exactly as the
plan declares it. Routing those uploads through the shared client's percent-encoding upload
round-trips fine against its own lookup but commits an entry the cache-hit check never matches, so
the second build misses cache and stalls. Related: claim filtering is by task *name*, and several
tests in that class share names, so a stalled build's task runs can be picked up by the next test
as if they were its own.

## Distributed end-to-end validation

`docker compose -f deploy/compose.yaml up --build` after `down -v`, so caches and database start
genuinely empty. Two Docker workers.

| Check | Result |
|---|---|
| MySQL, Kafka, Redis, MinIO reach healthy | yes |
| Migrations complete, control plane serves `/api/health` | yes |
| Workers register | yes |
| Cold build, empty cache, 2 workers | build 1 succeeded, 18.75 s, 25 tasks |
| Leaf-module incremental build | build 3 succeeded, 17.19 s, strict subset of tasks with a per-task reason |
| Warm cache re-run | build 5 succeeded in 0.068 s, fully cache-resolved |
| Worker killed mid-build | injected on build 6; the worker container exited |
| Lease expiry and reassignment | yes, trace below |
| Exactly one final result accepted | yes — 26 leases across 25 tasks, 25 successes |
| Temporary task containers removed | yes, none left behind |

### Crash-recovery trace (build 6, task `search:build`)

```
18:25:18.955  TASK_RUN_RUNNING     claimed by the first worker
              (worker killed)
18:27:49.066  TASK_RUN_RETRY_WAIT  lease expiry detected
18:28:00.554  TASK_RUN_READY       requeued
18:28:01.139  TASK_RUN_LEASED      claimed by the surviving worker
18:28:02.376  TASK_RUN_SUCCEEDED
```

- **Requeue to completion on another worker: 13.31 s.** This is the figure the resume cites.
- **Detection latency: 150.1 s**, and it is *not* a constant. A lease runs for the task's own
  configured timeout plus a grace period; the bundled demo declares `timeout: 2m`, which is where
  150 s comes from. A workload with tighter timeouts detects faster. Quoting 13.31 s as
  "detected and recovered in 13 s" would be wrong, so the resume says "recovered", not "detected".
- Duplicate accepted results: 0. Inconsistent artifacts: 0. `search:build` was leased twice and
  succeeded once.

The build still finished successfully (172.4 s wall) degraded to a single worker.

## What was not measured

- **Real AWS S3.** The AWS reference benchmark did not run: `aws sts get-caller-identity` returned
  `InvalidClientTokenId`. No AWS resource was provisioned, so there is nothing to tear down and no
  AWS cost was incurred — recurring AWS cost is `$0` because nothing was ever created. Every
  published figure is from the `local-benchmark` profile and is labelled as such. S3-compatible
  object storage *was* exercised, against MinIO, by the artifact contract suite.
- **Worker-count scaling across Docker workers.** The 1/2/4 scaling curve in `benchmarks.md` was
  measured with concurrent local executors (`forge run -j N`). The distributed path is validated
  above but its scaling curve was not benchmarked.
- **Public demo usage.** The static demo replays committed traces. No claim is made about visitors.

## Reproducing

```bash
export JAVA_HOME=<a JDK 21+ install>
./gradlew check
docker compose -f deploy/compose.yaml up --build -d
python3 benchmarks/scripts/run-benchmarks.py --warmups 3 --trials 10
python3 benchmarks/scripts/write-report.py
python3 benchmarks/scripts/export-traces.py
node benchmarks/scripts/validate-traces.mjs
```
