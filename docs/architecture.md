# Architecture

How CNBA is put together, and why it makes the choices it does.

## Modules

```text
apps/cli            picocli entry point; complete local mode
apps/control-plane  Spring Boot service: build/task state, MySQL, HTTP APIs, scheduler, Kafka
apps/worker         Docker-executing worker: registers, heartbeats, claims, runs, reports
libs/core           graph, change analysis, planning, local execution — no Spring
libs/config         cnba.yml parsing and strict validation
libs/cache          cache-key computation, deterministic archives, content-addressed storage
libs/protocol       worker <-> control-plane JSON request/response records, shared verbatim
libs/test-support   fixtures shared by other modules' tests
demo/sample-monorepo  bundled demo project used by the README walkthrough
```

The dependency direction is the main design constraint. `libs/core` has no
framework dependency at all, so planning and execution stay usable from the
CLI alone — local mode never needs a Spring classpath, a database, or a
network. `libs/cache` depends only on `libs/core`, which lets the control
plane reuse the exact cache-key algorithm and content hashing the CLI uses
rather than reimplementing them on the server side.

`libs/protocol` is deliberately dependency-light (Jackson only). Workers
never need Spring Boot just to talk to the control plane, and the control
plane uses the same records as its `@RequestBody`/`@ResponseBody` types, so
the two processes cannot drift on field names.

## From a change to a plan

1. `GitWorkspace` asks Git for the paths that differ from a base revision
   (`HEAD` by default), including staged, unstaged, and untracked files. A
   rename contributes both its old and new path.
2. Git reports paths relative to the repository root. A project may sit
   below that root — the bundled demo does — so paths are re-based onto the
   project directory and anything outside it is dropped.
3. `AffectedTaskAnalyzer` matches each changed path against declared task
   inputs, then follows reverse dependencies to close over every task whose
   output may change.
4. `PlanBuilder` orders the selected tasks topologically. A change to
   `cnba.yml` itself selects every task: the file can alter any command,
   input, or edge, and over-invalidating is the only safe answer.
5. Each selected task's cache key is computed and checked against the cache;
   a verified hit is reported as reused instead of run.

The closure in step 3 is a conservative "may change". A task inside it can
still turn out to be a cache hit — for example when an upstream task reran
but produced byte-identical output. Selection and reuse are separate
decisions on purpose: selection is cheap and graph-shaped, reuse is exact
and content-shaped.

## Cache keys

`CacheKeyCalculator` derives a task's key from a canonical serialization of:
the cache-key schema version; the task's declaration, command, and selected
(allowlisted) environment values; the sorted content digest of every file
matching a declared input glob; the artifact digest of each direct
dependency; and a toolchain fingerprint.

Never included: absolute paths, timestamps, random identifiers, or unrelated
environment variables. A relocated checkout produces the same key, which is
what makes an entry transferable between machines at all.

Dependencies contribute their *artifact digest*, not their cache key. That
is the difference between "an upstream task was selected" and "an upstream
task actually produced different bytes" — a comment-only edit recompiles to
identical class files, so nothing downstream of it moves.

Task outputs are archived deterministically (sorted paths, no timestamps,
only the executable bit preserved) and stored content-addressed under
`.cnba/cache/objects/`, alongside a manifest mapping the cache key to that
artifact's digest and size. A hit requires both a manifest *and* a stored
object whose bytes still match the recorded digest and size — a manifest on
its own is never enough, and a corrupted object is rejected and rebuilt.
Restoring a hit rejects any archive entry whose path would resolve outside
the project directory.

`CacheCoordinator` resolves decisions for one command invocation:
dependencies inside the selected set feed their real, just-computed artifact
digest forward; a dependency outside it reuses its last-recorded digest
without re-hashing anything. `cnba explain <task>` prints the key, its
per-contributor breakdown, and — on a miss — which specific contributor
changed, by diffing against the last key recorded for that task.

## Local execution

`LocalExecutor` owns a fixed-size thread pool. A task becomes runnable when
every *selected* dependency has succeeded; dependencies outside the selected
set count as already satisfied, which is what makes an incremental run
incremental. When a task fails or times out, everything downstream is marked
skipped rather than run, while independent branches carry on.

`ProcessTaskRunner` starts each task as a direct child process — never
through a shell, so nothing in `cnba.yml` can be read as shell syntax. It
starts from an empty environment and passes through only `PATH`, `HOME`,
`TMPDIR`, `LANG`, and the task's declared environment allowlist, so a task's
result depends on what it declares. Output from both streams is merged,
bounded, and forwarded line by line with the task name attached.

On timeout or cancellation the runner signals the whole process tree, waits
a short grace period, then kills whatever is still alive. Ctrl-C reaches a
JVM as shutdown rather than an exception, so `cnba run` installs a shutdown
hook that interrupts the run thread — the signal the executor turns into
terminating tasks.

## Control plane

`apps/control-plane` is a Spring Boot service with MySQL as the
authoritative store for accepted build and task state. It never executes a
task itself; it tracks what a CLI or worker reports and hands out work
through the scheduler.

Flyway migrations (`db/migrations/`) create the schema up front: `projects`,
`plan_submissions` and `task_definitions`, `builds`, `task_runs`,
`task_attempts`, `artifacts`, `cache_entries`, `workers`, and
`build_events`.

`Build` (`CREATED → PLANNING → RUNNING → {SUCCEEDED, FAILED, CANCELED}`) and
`TaskRun` (`PENDING → READY → LEASED → RUNNING → {SUCCEEDED, FAILED,
RETRY_WAIT}`, `READY → CACHED`, `PENDING → SKIPPED`) each have a dedicated
state machine that is the only path to mutating state. It validates the
transition against a fixed allowed-edges table, rejects it if the caller's
expected version no longer matches the persisted row (optimistic locking via
a JPA `@Version` column), and emits exactly one ordered `BuildEvent` per
accepted transition. Both machines lock the owning `Build` row
(`SELECT ... FOR UPDATE`) for the duration of the transition, so a build's
event sequence numbers stay gap-free under concurrent transitions on the
build or any of its task runs.

Submitting the same plan revision twice, or creating a build for the same
plan submission twice, returns the original row rather than a duplicate.
Creating a build materializes a `TaskRun` per selected task; a task with no
in-build dependency goes straight to `READY` (a dependency outside the
selected set was already satisfied before the plan was built, so it never
blocks readiness), and from there to `CACHED` if a verified artifact already
exists for its cache key. A build whose every task is a cache hit completes
without ever reaching a worker.

Every request carries a correlation id (from the caller, or generated)
through MDC, and logs emit as JSON. Micrometer counters and timers cover
build starts/completions/duration, task attempts/retries/duration, and
ready-queue depth.

### On exactly-once

CNBA does not claim exactly-once execution anywhere, and the design is
shaped around not needing it. A task can genuinely run more than once — a
retry after a crash, or two racing attempts under speculation. What is
guaranteed is **idempotent acceptance**: exactly one result is ever accepted
per task run. `TaskRun.winningAttemptNumber` is the atomicity point, claimed
by a single conditional `UPDATE ... WHERE winning_attempt_number IS NULL`,
so of several concurrent reports exactly one sees an updated row and may
apply its result. Every other attempt is rejected outright, including one
whose worker resumes from a stall and reports independently.

## Remote artifact cache

Artifact bytes live in S3 (or any S3-compatible store; the Compose stack
uses MinIO). The upload flow is deliberately paranoid about what it accepts:
the client archives a task's outputs and computes the digest and size
locally, `POST /api/artifacts` uploads those bytes plus the declared
digest/size, and the control plane recomputes the digest itself rather than
trusting the claim. It writes to a temp key, verifies what actually landed,
copies to the final content-addressed key
(`artifacts/<first two digest chars>/<digest>`), records the `Artifact` row,
and transactionally associates the cache key with it via a `CacheEntry` row
before deleting the temp object.

Any mismatch along the way leaves no `Artifact` and no `CacheEntry` behind.
`GET /api/artifacts/lookup` re-fetches the object and re-verifies digest and
size before returning it — a stored object that no longer matches its
recorded digest is reported as `409 artifact_corrupt`, never as a hit. A
scheduled sweep removes anything left under the temp prefix past its TTL,
for uploads a client abandoned mid-flight.

`TaskCache` takes an optional `RemoteArtifactClient`: a lookup checks local
first and only falls back to remote on a local miss, adopting a remote hit
into the local cache; a fresh store always writes locally first, then
best-effort mirrors to remote. `apps/cli` wires this in only when
`CNBA_CONTROL_PLANE_URL` is set. Unset, `cnba plan`/`cnba run` require no
infrastructure at all, and a configured-but-unreachable remote degrades to
local-only rather than failing the command.

AWS configuration: leave `CNBA_S3_ENDPOINT` unset to use real S3 with the
default credentials provider chain (IAM role, environment, or
`~/.aws/credentials`) and virtual-hosted addressing, with
`CNBA_S3_BUCKET`/`CNBA_S3_REGION` naming the bucket. Setting
`CNBA_S3_ENDPOINT` switches to path-style addressing with static
credentials, which is the local development path in `deploy/compose.yaml`.
The bucket is provisioned out of band in production with an IAM policy
scoped to the artifact prefix; the control plane only auto-creates it as a
development convenience.

## Scheduling and workers

Workers register, heartbeat, and claim from one global ready queue. Each
claim takes a lease with a token and an expiration; `Worker.maxConcurrency`
bounds how many a worker may hold. `apps/worker` runs each task in its own
Docker container, archives its outputs, uploads them, and reports the
result.

Two scheduling policies ship, selected by configuration: FIFO, and a
duration-aware critical-path policy that weights each ready task by the
longest remaining chain beneath it, using `TaskDurationEstimator`'s medians
from prior runs. Measured comparison in [benchmarks.md](benchmarks.md).

## Failure recovery

Every lease and heartbeat decision is made by MySQL.
`SchedulerService.reclaimExpiredLeases` and
`WorkerService.markStaleWorkersUnhealthy` sweep unconditionally on a fixed
schedule and are the sole source of truth.

Redis only accelerates *detecting* the same condition. On lease grant the
scheduler also sets `cnba:lease:<taskRunId>` with a TTL matching the lease
expiration; on heartbeat, `cnba:worker:heartbeat:<workerId>` with a TTL of
three heartbeat intervals. `RedisConfig` enables keyspace expiry
notifications and `ExpiredKeyListener` subscribes — the moment either key
lapses, it calls the same reclaim logic the periodic sweep would reach on
its own, just sooner. Every accelerated call re-validates against MySQL's
own timestamps before mutating anything, so a stale or spurious Redis event
can never cause an incorrect transition.

`reconcileRedisFromDatabase` and `reconcileRedisLeases` periodically re-arm
Redis from MySQL's current state. That is what makes recovery after a Redis
flush correct rather than merely non-fatal: the sweeps never depended on
Redis being populated, and reconciliation restores the acceleration once
Redis is back.

Detection does not wait on the task's own lease deadline. When a worker is
declared dead, `reclaimLeasesOfWorker` immediately fails every attempt it
was holding, rather than leaving them to expire on a `lease_expiration`
derived from the task's declared timeout — which for a slow task could
dominate recovery time by itself. A reclaimed attempt also skips the
exponential retry backoff a genuinely failing task still gets: backoff
exists to stop hammering work that keeps failing, and a reclaimed lease is
evidence the *worker* disappeared, not that the work was bad. Together these
cut measured recovery on a 60-second-timeout task from over a minute to
single-digit seconds.

Every interval this depends on (`cnba.worker.heartbeat-interval-ms`,
`cnba.scheduler.lease-grace-seconds`, `-lease-sweep-interval-ms`,
`-retry-sweep-interval-ms`, `-reclaim-retry-delay-ms`) is an
`application.yml` default behind a `CNBA_*` override, so a deployment can
tune detection speed against heartbeat cost without a code change.

Crash injection is a two-step, backend-only mechanism used by tests and the
demo: `POST /api/workers/{id}/crash` sets a flag on the `workers` row; the
worker consumes it on its next heartbeat response and calls `Runtime.halt` —
an abrupt, no-shutdown-hook stop, so an in-flight lease is left to expire
exactly as a real crash would leave it.

## Speculative execution

The same lease design extends to slowdowns rather than failures. A `TaskRun`
does not own its lease directly — the columns on the row are a denormalised
mirror of the newest live attempt, and each `TaskAttempt` carries its own.

When a worker finds no claimable work, `SchedulerService.speculateFor` looks
for a running attempt with no live sibling that has been running past a
multiple of its `TaskDurationEstimator` median (floored by a minimum
elapsed time, capped per build) and starts a second, independent attempt on
that otherwise-idle worker. A task with no duration history is never treated
as a straggler — there is nothing to be slow relative to.

Because this only fires once a worker has already found no unstarted work,
speculation can never take a slot an unstarted task would have used. The
trade it makes is bounded duplicate compute for tail latency, not throughput
for tail latency. It is off by default, since that trade is only worth
making on a cluster with genuinely idle capacity.

## Public demo

`dev.cnba.controlplane.demo` wraps the trusted `PlanSubmissionService`/
`BuildService` API with a guest-safe surface. It never reimplements
scheduling — it only builds requests for the existing services to accept.

Every guest visit computes a real plan against the bundled
`demo/sample-monorepo`. `DemoWorkspace` mutates a shared copy of the repo by
running `scripts/apply-scenario` — the one place a scenario's file changes
are defined, run identically by the control plane for hashing and by workers
as a prefix on every task command — and `DemoPlanFactory` turns the mutated
tree into real task definitions using the same analyzer and key calculator
the CLI uses.

A visit submits *two* real, concurrently scheduled builds against that tree:
a full rebuild standing in for a traditional CI system, and the
affected-only incremental build. The comparison shown is two genuine
measured runs, never a live run next to a precomputed number. The
traditional side's cache keys are salted per visit — without that, once any
guest had run a scenario, a later guest's "traditional" build would start
hitting the warm cache and stop resembling an uncached system.

`DemoGuestGuard` enforces a single guest build in flight and per-client rate
limiting via Redis TTL keys, both failing closed if Redis is unreachable.
Guest input is a fixed enum of scenarios; there is no command, path, or
image field a visitor can set.

The `ui/` app (Vite + React + TypeScript) is the guest-facing surface: a
scenario picker, a live dependency graph, two terminal panels streaming real
per-task lines from `GET /api/builds/{id}/events` over SSE, live timers, a
result card populated from measured values, and a `Crash a Worker` action
wired to the crash-injection endpoint.

## Self-hosting

The repository root carries its own `cnba.yml` (17 tasks) describing the
Gradle multi-module graph one level down: a `<module>:build` /
`<module>:test` pair per module wired to the real `project(":...")`
dependencies in each `build.gradle.kts`, plus `ui:build` / `ui:test`.

`.github/workflows/cnba.yml` runs on every pull request: it resolves the
merge-base against the base branch, runs `cnba plan --base <merge-base>`
then `cnba run --base <merge-base>`, and feeds both outputs to
`.github/scripts/cnba-summary.py`, which parses the CLI's fixed-width
result rows into `cnba-summary.json`. That uploads as an artifact and
drives a PR comment reporting run/cached/unaffected and
succeeded/failed/skipped counts, updating in place on later pushes.
`.cnba/cache` is persisted across runs with `actions/cache`, keyed by run
id with a prefix fallback — the mechanism that lets one PR's second CI run
reuse the first run's outputs.

Applying the system to itself produced measurable results and surfaced two
real design points:

- A one-line change to `libs/core` selects 12 of 17 tasks — every module
  depending on `core`, directly or transitively — and no `ui:*` task. The
  same change restricted to `ui/src/main.tsx` selects 2 tasks and no Gradle
  module.
- Across two isolated CI runs on one PR, a cold run executed all 17 tasks in
  **1m25s**; the next push changed only `ui/src/main.tsx`, restored the
  previous run's cache, reported 15 of 17 tasks reused, and finished in
  **4.4s** — different runner VM, different checkout, same artifacts.
- Between a developer machine and CI, reuse is conditional by design.
  `ToolchainFingerprint.current()` is a direct cache-key input, so an entry
  only transfers to a machine running the identical JVM build — major *and*
  patch. That is the cache being correct rather than convenient; loosening
  it would require proving the JVM patch level cannot affect compiled
  output.

Every Gradle task declares `environment: ["JAVA_HOME"]`. Because
`ProcessTaskRunner` starts from an empty environment by design, without that
allowlist entry `./gradlew` falls back to the system default JVM instead of
the one `cnba` resolved — a gap self-hosting surfaced, fixed in
`cnba.yml` rather than in the runner, since the environment-allowlist
field is exactly what it is for.

## Testing

Tests are organized by what they prove, not by which module they live in.

**Unit** — graph construction, cycle detection, topological ordering,
reverse closure, critical-path weighting, glob matching, cache-key
canonicalization, state transitions, retry policy.

**Property** — every emitted execution order respects dependencies, and
permuting how a graph is declared never changes the selected plan
(`GraphPropertyTest`, 300 seeded random DAGs); the same canonical inputs
always produce the same cache key, and changing any single declared
contributor always changes it (`CacheKeyPropertyTest`, 150 seeded
scenarios); no accepted result ever transitions twice, under a generated
storm of duplicate, cnbad-lease, and contradictory reports over real HTTP
(`ResultIdempotencePropertyTest`).

**Integration** (Testcontainers, real services) — MySQL migrations and
restart survival, S3 upload/verify/restore, Kafka redelivery, Redis TTL and
flush recovery, the full control-plane/worker protocol, duplicate result
handling.

**End-to-end** — local cold/warm builds, a remote incremental build across
two independent workspaces sharing one control plane over the real wire
protocol, worker crash recovery, the guest demo under Playwright.

**Concurrency** — two builds in flight complete independently with no task
executed twice; multiple workers never double-claim; a result reported at
its own lease's expiry is accepted or rejected but never double-applied;
several clients committing identical artifact bytes produce exactly one
artifact row.

**Security** — path traversal in artifacts, command-array validation (both
runners invoke via argv, never `sh -c`), public endpoint rate limiting,
output-size caps that are verified to actually truncate, timeout
enforcement, and container cleanup after both normal and killed tasks.

Two bugs worth recording, because the tests that found them were written
before the bugs were known:

- **Symlinks were followed out of the project directory.**
  `ProjectFiles.matching` walked with `Files.isRegularFile`, which follows
  symlinks, so a symlink inside a project could pull an arbitrary host file
  into a cache key and into a shared artifact. Fixed with
  `LinkOption.NOFOLLOW_LINKS`.
- **Concurrent uploads of identical bytes raced past the dedupe check.**
  Two callers uploading the same bytes could both pass the
  existing-artifact lookup before either inserted, and Hibernate refuses
  further statements on a session after a flush-time constraint violation,
  so catching and refetching in the same transaction could not recover.
  Fixed by running the insert in its own `REQUIRES_NEW` transaction so a
  duplicate-key failure can fall back to a clean read.

## CI/CD

`.github/workflows/cnba.yml` runs on every pull request against `main`,
alongside the self-hosting `plan-and-run` job:

- **`required-checks`** — `./gradlew check`, which wires Spotless (Google
  Java Format, AOSP variant) and SpotBugs into `check` alongside every
  module's tests and the control plane's Testcontainers `integrationTest`.
  Frontend lint, typecheck, and unit tests run alongside.
- **`container-images`** — both Dockerfiles build from a clean checkout.
- **`dependency-security-scan`** — Trivy filesystem scan over Gradle and npm
  lockfiles at `HIGH,CRITICAL`, failing the job on a hit. Chosen over an
  OWASP-dependency-check/NVD setup because it needs no secret and no
  rate-limited external service to be reliable in CI.
- **`browser-smoke-test`** — brings up the real `deploy/compose.yaml` stack,
  serves the UI against it, and drives it with Playwright/Chromium.

`.github/workflows/release.yml` runs on push to `main`: required checks,
versioned artifacts (by commit SHA, never a hand-maintained number that can
drift from what shipped), SHA-tagged immutable container images, migrations
(applied by Flyway at control-plane startup — deploying the service *is* how
migrations apply), control plane and workers deployed, explicit
MySQL/S3/Kafka/Redis connectivity verification via each service's own
healthcheck, a public demo scenario run end to end, and a check that the
deployed instance's `GET /api/version` echoes the exact commit SHA just
deployed.

## Deployment profiles

There is no continuously hosted backend. Running the distributed system is
something you do on demand; the only permanently public artifact is a static
page.

| Profile | What it is | Storage | Lifetime |
|---|---|---|---|
| `deploy/compose.yaml` | the normal two-worker development stack | MinIO | on demand |
| `deploy/local-benchmark/` | the same stack with workers scaled for worker-count comparisons | MinIO | on demand |
| `deploy/aws-reference/` | one EC2 host against real Amazon S3 | Amazon S3 | minutes, then destroyed |

`aws-reference` is temporary by construction: `terraform destroy` is the
only teardown path, the root volume is `delete_on_termination`, the bucket
is `force_destroy` with a one-day lifecycle rule, and the public IPv4 is
auto-assigned rather than an allocated Elastic IP. Its wrapper script runs
`destroy` and `audit` even when an earlier step fails, and `audit` exits
non-zero if any billable tagged resource survives. Details in
[../deploy/aws-reference/README.md](../deploy/aws-reference/README.md).

## Execution traces and the static demo

`demo/traces/trace.schema.json` (v1) is one versioned contract shared by
benchmark evidence and the public demo: DAG nodes and edges, changed files,
per-task status/reason/duration/executor, ordered events with millisecond
offsets, totals, and the baseline the run is compared against.

Traces are recordings of real `cnba run` executions produced by
`benchmarks/scripts/export-traces.py`. `validate-traces.mjs` enforces the
schema *and* cross-checks each trace against itself — that totals match the
task list, that every edge references a real node, that every task is a
graph node. It runs in CI and again before the demo is published, so a trace
that contradicts itself cannot reach the page.

`ui/` builds in two modes. With `VITE_STATIC_DEMO=true` the landing route
becomes the trace-replay showcase, API-backed routes are dropped from the
router, and the result is a fully static bundle: no control plane, no cloud
call, no visitor-supplied code. It publishes to GitHub Pages from
`.github/workflows/pages.yml`. Because Pages serves from `/<repo>/`, the
router takes its `basename` from `import.meta.env.BASE_URL`; a build served
under a prefix but routed at `/` renders an empty page that still returns
HTTP 200 with the correct title, so `ui/e2e/static-demo.spec.ts` checks the
rendered DOM at the real base path, including with every external request
blocked.

## The `./cnba` launcher

`./cnba` is a POSIX shell script at the repository root, not a packaged
binary. It resolves a Java 21+ runtime (from `JAVA_HOME`, `PATH`,
`/usr/libexec/java_home`, or conventional install locations), runs
`./gradlew :apps:cli:installDist` if the CLI has not been built yet, then
execs the generated launcher. It does not change directory, so the project
being planned is whatever directory you are standing in.

## Exit codes

`0` success; `1` the build ran and a task failed, timed out, or was skipped
behind a failure; `2` CNBA could not run at all — invalid `cnba.yml`,
a cyclic graph, no repository, bad usage. Expected failures print one
actionable message and no stack trace.
