# Architecture

What exists today. Sections are added as later capabilities land; nothing
here describes work that is not in the repository.

## Modules

```text
apps/cli            picocli entry point; complete local mode
apps/control-plane  Spring Boot service: build/task state, MySQL, HTTP APIs, scheduler, Kafka
apps/worker         Docker-executing worker: registers, heartbeats, claims, runs, reports
libs/core           graph, change analysis, planning, local execution — no Spring
libs/config         forgeci.yml parsing and strict validation
libs/cache          cache-key computation, deterministic archives, local content-addressed storage
libs/protocol       worker <-> control-plane JSON request/response records, shared verbatim
libs/test-support   fixtures shared by other modules' tests
demo/sample-monorepo  bundled demo project used by the walkthrough in the README
```

`libs/core` deliberately has no framework dependency so planning and
execution stay usable from the CLI alone. `libs/cache` depends only on
`libs/core` — the same cache-key algorithm and content-hashing (`Digests`)
are reused by the control plane's remote artifact verification, and by
`apps/worker` for archiving and uploading a task's outputs after it runs.
`libs/protocol` is dependency-light (Jackson only, no Spring) so
`apps/worker` never needs a Spring Boot classpath just to talk to the
control plane; `apps/control-plane` uses the same records as its
`@RequestBody`/`@ResponseBody` types, so the two processes cannot drift on
field names. `apps/control-plane` depends on `libs/cache` for digest
computation and cache-hit verification only — it is still a standalone
service the CLI and workers submit plans, builds, artifacts, and results
to, not a consumer of local-mode planning or execution code.

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
   `forgeci.yml` itself selects every task: the file can alter any
   command, input, or edge, and over-invalidating is the only safe answer.
5. Each selected task's cache key is computed and checked against the
   local cache; a verified hit is reported as reused instead of run. The
   affected-task closure above is a conservative "may change" — a task
   inside it can still turn out to be a cache hit, e.g. when an upstream
   task reran but produced byte-identical output.

## Local cache

`CacheKeyCalculator` (in `libs/cache`) derives a task's key from a
canonical serialization of: the cache-key schema version; the task's
declaration, command, and selected (allowlisted) environment values; the
sorted content digest of every file matching a declared input glob; the
artifact digest of each direct dependency; and a toolchain fingerprint
(currently the running JVM's version). Never included: absolute paths,
timestamps, random identifiers, or unrelated environment variables — a
relocated checkout produces the same key.

A task's outputs are archived deterministically (sorted paths, no
timestamps, only the executable bit kept) and stored content-addressed
under `.forge/cache/objects/`, alongside a manifest mapping the cache key
to that artifact's digest and size. A cache hit requires both a manifest
and a stored object whose bytes still match the digest and size the
manifest recorded — a manifest existing on its own is never enough, and a
corrupted object is rejected and rebuilt. Restoring a hit rejects any
archive entry whose path would resolve outside the project directory.

`CacheCoordinator` (in `apps/cli`) resolves decisions for one command
invocation: dependencies inside the selected set feed their real,
just-computed artifact digest forward; a dependency outside it (unaffected
by this run) reuses its last-recorded digest without re-hashing anything.
`forge explain <task>` shows the key, its per-contributor breakdown, and —
on a miss — which specific contributor changed, by diffing against the
last key recorded for that task.

## Local execution

`LocalExecutor` owns a fixed-size thread pool. A task becomes runnable when
every *selected* dependency has succeeded; dependencies outside the
selected set count as already satisfied, which is what makes an
incremental run incremental. When a task fails or times out, everything
downstream of it is marked skipped rather than run, while independent
branches carry on.

`ProcessTaskRunner` starts each task as a direct child process — never
through a shell, so nothing in `forgeci.yml` can be read as shell syntax.
It starts from an empty environment and passes through only `PATH`, `HOME`,
`TMPDIR`, `LANG`, and the task's declared environment allowlist, so a
task's result depends on what it declares. Output from both streams is
merged, bounded, and forwarded line by line with the task name attached.

On timeout or cancellation the runner signals the whole process tree, waits
a short grace period, then kills whatever is still alive. Ctrl-C reaches a
JVM as shutdown rather than an exception, so `forge run` installs a
shutdown hook that interrupts the run thread — the signal the executor
turns into terminating tasks.

## Control plane

`apps/control-plane` is a Spring Boot service with MySQL as the
authoritative store for accepted build/task state — Redis (added in phase
6, see "Failure recovery" below) only ever accelerates detecting a dead
worker or expired lease, never the record of whether one happened. This
service itself never executes a task; it only tracks state a CLI or
worker reports and hands out work through the scheduler described below.

Flyway migrations (`db/migrations/`, on the service's classpath under
`migrations/`) create the schema up front: `projects`, `plan_submissions`
and `task_definitions` (a plan's selected tasks, their cache keys, and —
since phase 5 — the command/outputs/environment/timeout a worker needs to
actually run one), `builds`, `task_runs` (worker id, lease token/
expiration, readiness/retry timestamps, and critical-path weight, all
added by `V2__workers_and_scheduling.sql`), `task_attempts`, `artifacts`,
`cache_entries`, `workers`, and `build_events`.

`Build` (`CREATED → PLANNING → RUNNING → {SUCCEEDED, FAILED, CANCELED}`)
and `TaskRun` (`PENDING → READY → LEASED → RUNNING → {SUCCEEDED, FAILED,
RETRY_WAIT}`, `READY → CACHED`, `PENDING → SKIPPED`) each have a dedicated
state machine (`BuildStateMachine`, `TaskRunStateMachine`) that is the only
path to mutating either entity's state: it validates the transition
against a fixed allowed-edges table, rejects it outright if the caller's
expected version no longer matches the persisted row (optimistic locking
via a JPA `@Version` column), and emits exactly one ordered `BuildEvent`
per accepted transition. Both machines lock the owning `Build` row
(`SELECT ... FOR UPDATE`) for the duration of the transition so that a
build's event sequence numbers — assigned by counting existing events for
that build — stay gap-free under concurrent transitions on the build
itself or any of its task runs.

Submitting the same plan revision (`project id`, `revision`,
`base revision`) twice, or creating a build for the same plan submission
twice, returns the original row rather than creating a duplicate — the
"no exactly-once execution, only idempotent acceptance" invariant applies
to submission as much as to execution. Creating a build materializes a
`TaskRun` per selected task; a task with no in-build dependency goes
straight to `READY` (a dependency outside the selected set was already
satisfied before the plan was built, so it never blocks readiness), and
from there straight on to `CACHED` if a verified artifact already exists
for its cache key (`SchedulerService.promoteToReadyOrCached`) — a build
whose every task is a cache hit completes without ever reaching a worker.

`GET /api/builds/{id}/artifacts` returns whatever `Artifact` rows this
build's task runs actually reference, from a real verified upload or a
cache hit — never fabricated. `GET /api/health` reports liveness
unconditionally; `GET /api/ready` probes
the datasource and fails closed if MySQL is unreachable. Every request is
tagged with a correlation id (from the caller, or generated) and, where the
URL carries them, project/build ids, via `CorrelationIdFilter` and MDC;
logs are emitted as JSON (Logstash encoder). Micrometer counters and timers
cover build starts/completions/duration, task attempts/retries/duration,
and ready-queue depth — real values driven by actual transitions, not
placeholders.

## Remote artifact cache

`apps/control-plane` now backs the content-addressed artifact protocol with
S3 (or an S3-compatible service for local/Compose dev — MinIO). The upload
flow matches architecture.md's protocol exactly: the CLI archives a task's
outputs and computes its digest and size locally (unchanged from the local
cache in `libs/cache`), `POST /api/artifacts` uploads those bytes plus the
declared digest/size, the control plane recomputes the digest itself
(never trusting the caller's claim alone), writes to a temp key, verifies
what actually landed in S3, copies it to its final content-addressed key
(`artifacts/<first two digest chars>/<digest>`), records the `Artifact`
row, and transactionally associates the cache key with it via a
`CacheEntry` row — then deletes the temp object. Any mismatch along the
way (wrong size, wrong digest, an S3 error) leaves no `Artifact` and no
`CacheEntry` behind. `GET /api/artifacts/lookup` resolves a cache key,
re-fetches the object, and re-verifies its digest and size before
returning it — a stored object that no longer matches its recorded digest
is reported as `409 artifact_corrupt`, never as a hit. A scheduled sweep
(`TempUploadCleanupService`) removes anything left under the temp prefix
past its TTL, for uploads a client abandoned mid-flight.

`libs/cache`'s `TaskCache` gained an optional `RemoteArtifactClient`: a
lookup checks the local cache first and only falls back to the remote
store on a local miss (adopting a remote hit into the local cache too), a
fresh store always writes locally first and then best-effort mirrors to
remote. `apps/cli` wires this in only when `FORGE_CONTROL_PLANE_URL` is
set — unset, `forge plan`/`forge run` are unchanged phase 1/2 behavior
with zero infrastructure, and a configured-but-unreachable remote degrades
to local-only rather than failing the command. The same `CacheKey` value
already used locally is reused as the remote lookup key, so the
relocatable-key property from phase 2 carries through unchanged.

Production S3 configuration: point `FORGE_S3_ENDPOINT` at nothing (leave
it unset) to use real AWS S3 with the default credentials provider chain
(IAM role, environment, or `~/.aws/credentials`) and virtual-hosted
addressing; set `FORGE_S3_BUCKET`/`FORGE_S3_REGION` to the provisioned
bucket. Setting `FORGE_S3_ENDPOINT` switches to path-style addressing with
static credentials (`FORGE_S3_ACCESS_KEY`/`FORGE_S3_SECRET_KEY`) — the dev
path in `deploy/compose.yaml`. The bucket itself is provisioned out of
band in production (IAM policy scoped to the artifact prefix); the control
plane only auto-creates it as a dev/test convenience when missing.

## Failure recovery

Every lease and worker heartbeat is still decided by MySQL —
`SchedulerService.reclaimExpiredLeases` (a lease past `lease_expiration`)
and `WorkerService.markStaleWorkersUnhealthy` (a worker silent past three
heartbeat intervals) sweep it unconditionally on a fixed schedule and are
the sole source of truth. Redis only accelerates *detecting* the same
condition: on lease grant, `SchedulerService` also sets a Redis key
(`forge:lease:<taskRunId>`) with a TTL matching `lease_expiration`; on
heartbeat, `WorkerService` sets `forge:worker:heartbeat:<workerId>` with a
TTL of three heartbeat intervals. `RedisConfig` enables Redis's `expired`
keyspace notifications and `ExpiredKeyListener` subscribes to them —
the moment either key's TTL lapses, it calls the same reclaim/unhealthy
logic the periodic sweep would eventually reach on its own, just sooner.
Every accelerated call re-validates against MySQL's own timestamp before
mutating anything, so a stale or spurious Redis event can never itself
cause an incorrect transition. `WorkerService.reconcileRedisFromDatabase`
and `SchedulerService.reconcileRedisLeases` run periodically to re-arm
Redis's keys from MySQL's current state, which is what makes recovery
after a Redis flush or restart correct rather than merely "doesn't
crash": the sweeps never depended on Redis being populated in the first
place, and reconciliation restores the acceleration once Redis is back.

Idempotency was mostly already in place from phase 5's lease design —
this phase's job was making it hold under real concurrent failure rather
than just documenting it. `SchedulerService.reportResult` rejects (`403
lease_rejected`) any report whose worker id, lease token, or attempt
count no longer matches the task run's current lease, and returns the
existing result unchanged (rather than raising) for a report that matches
an already-*accepted* result — so neither a late report from an expired
lease nor a redelivered duplicate (HTTP retry or Kafka redelivery,
`TaskResultKafkaListener` routes through the identical method) can
overwrite or re-apply an effect. Artifact acceptance is idempotent the
same way phase 4 already made it: `RemoteArtifactService.commit` looks up
an existing `Artifact` row by digest before ever writing a new one, so two
attempts producing byte-identical output — the common case after a
crash-and-retry — never create a second artifact or cache entry.

Crash injection is a two-step, backend-only mechanism (the public "Crash a
Worker" button is phase 7's job): `POST /api/workers/{id}/crash` sets a
`crash_requested` flag on the `workers` row; the worker consumes and
clears it on its very next heartbeat response
(`HeartbeatResponse.shouldCrash`) and calls `Runtime.halt` — an abrupt,
no-shutdown-hook JVM stop, so any in-flight task's lease is left to simply
expire exactly as a real crash would leave it, rather than releasing
cleanly.

`FailureRecoveryIntegrationTest` covers all seven required failure
scenarios (crash before/during execution, duplicate result, late result
after lease expiration, delayed artifact commit, Redis flush mid-build,
Kafka redelivery — the last already proven by
`KafkaTaskResultsIntegrationTest` from phase 5) against the real
Testcontainers-backed stack, and records recovery time with
`libs/test-support`'s `RecoveryTimer`. A manual demo against the full
Compose stack (two workers, `docker kill` on the one holding an in-progress
task's lease) confirmed the same behavior end to end: the crash was
detected via the Redis-accelerated path within seconds of the missed
heartbeat, the lease was reclaimed once it passed `lease_expiration`, the
task was reassigned to and completed by the surviving worker, and exactly
one artifact was committed — no duplicate `cache_entries`/`artifacts` row.
Total wall-clock recovery time in that run was dominated by the demo's
deliberately generous task timeout and lease grace period, not by the
recovery mechanism itself.

## Public demo

`dev.forgeci.controlplane.demo` wraps the trusted `PlanSubmissionService`/
`BuildService` API with a guest-safe surface (`POST /api/demo/builds`,
`POST /api/demo/builds/{id}/crash-worker`, `GET /api/benchmarks/latest`) —
it never reimplements scheduling, it only ever builds requests for the
existing services to accept. Every guest visit computes a real plan
against the bundled `demo/sample-monorepo`: `DemoWorkspace` mutates a
single shared, mutable copy of the repo by running
`scripts/apply-scenario` (the one place a scenario's file changes are
defined — both the control plane, for hashing, and workers, prefixed onto
every task's command, run the identical script), and `DemoPlanFactory`
turns the mutated tree into real `TaskDefinitionRequest`s using the same
`AffectedTaskAnalyzer`/`CacheKeyCalculator` the CLI uses locally.

A guest visit submits *two* real, concurrently scheduled builds against
that same mutated tree — a full rebuild standing in for a traditional CI
system, and the affected-only incremental build — so the UI's comparison
is two genuine measured runs, never a live run next to a precomputed
number. The traditional-build side's cache keys are salted with a random
value unique to that visit (`DemoPlanFactory.buildBaselineForComparison`):
without that, once any guest had run a given scenario once, a later
guest's "traditional" build would also start hitting the now-warm cache
and stop looking like an uncached system. `DemoGuestGuard` enforces a
single guest build in flight at a time and per-client rate limiting via
plain Redis TTL keys (`SETNX`), matching contracts.md's "Redis is
acceleration/ephemeral only" rule — both checks fail closed if Redis is
unreachable. `DemoBuildWatcher` releases the in-flight slot once every
build from a visit reaches a terminal state, with the lock's own TTL as a
backstop.

Building this surfaced two latent bugs in the phase 3/5 scheduler, neither
specific to the demo — any real affected-subset plan could have hit them:
`SchedulerService.promoteReadyDependents` treated a dependency absent from
the submitted plan (because it was unaffected and never included) as
*unsatisfied* rather than *already satisfied*, permanently starving any
dependent that also had at least one dependency actually in the plan; and
`promoteToReadyOrCached` never cascaded to a cache-hit task's dependents at
all, since nothing calls `reportResult` for a task that never reached a
worker — so a cache hit anywhere with a dependent stranded the rest of the
build in `PENDING` forever. Both are fixed and covered by dedicated
`WorkerSchedulingIntegrationTest` cases that fail without the fix.

The `ui/` app (Vite + React + TypeScript) is the one guest-facing
surface: an instructions screen with a `Begin` action and a scenario
picker, then a live dependency graph and two terminal-style panels
(traditional vs. ForgeCI) streaming real per-task lines from
`GET /api/builds/{id}/events` (Server-Sent Events over a short poll of
`BuildEventRepository` — the first read-side exposure of the build event
log), bottom-pinned live timers, a result card populated from measured
values once both builds finish, and a `Crash a Worker` action wired to
the existing crash-injection endpoint.

## Self-hosting and CI

The repository root carries its own `forgeci.yml` (17 tasks) describing
the Gradle multi-module graph one level down: a `<module>:build` /
`<module>:test` pair per module, wired to the real `project(":...")`
dependencies in each `build.gradle.kts` (e.g. `apps/control-plane` depends
on `core`, `config`, `cache`, and `protocol`, so its tasks declare all
four as `depends_on`), plus `ui:build` / `ui:test` for the Vite/React
frontend. Every Gradle-invoking task declares `environment: ["JAVA_HOME"]`:
`ProcessTaskRunner` starts each task from an empty environment by design
(phase 2), so without that allowlist entry `./gradlew` fell back to the
system default JVM (11) instead of the one `forge` itself resolved (21) —
a real gap self-hosting surfaced, not a hypothetical one, fixed in
`forgeci.yml` rather than in `ProcessTaskRunner` since the fix is exactly
what the existing environment-allowlist field is for. Self-hosting also
found `ui/`'s `npm test` had no `vitest` exclusion for `e2e/`, so it
picked up a Playwright spec as a broken vitest suite; fixed with a `test:`
block in `ui/vite.config.ts`.

The CI wrapper (`.github/workflows/forgeci.yml`) runs on every pull
request against `main`: resolves the merge-base of the PR against
`origin/<base>`, runs `forge plan --base <merge-base>` then
`forge run --base <merge-base>`, and feeds both text outputs to
`.github/scripts/forgeci-summary.py`, which parses the CLI's fixed-width
`Result`/`Plan` rows (the same format `PlanCommandTest`/`RunCommandTest`
pin) into `forgeci-summary.json` — `{plan: {run, cached, unaffected,
tasks}, run: {succeeded, failed, skipped, tasks}, ok}`. That file uploads
as a workflow artifact on every run, and a PR comment (via
`actions/github-script` and the default `GITHUB_TOKEN` — no additional
credential wiring needed) reports the run/cached/unaffected and
succeeded/failed/skipped counts, updating the same comment on later
pushes rather than piling up new ones. `.forge/cache` (gitignored locally)
is persisted across workflow runs with `actions/cache`, keyed by run id
with a prefix `restore-keys` fallback — the mechanism that lets one PR's
second CI run reuse the first run's cached task outputs instead of
rebuilding.

Measured evidence from applying this to ForgeCI's own repository:

- A one-line change to `libs/core/.../Durations.java` produces a plan of
  12 run / 0 cached / 5 unaffected — every module that depends on `core`
  (directly or transitively) is selected, `ui:*` is not. Answers "can a
  frontend-only change avoid rebuilding the backend and vice versa?" in
  both directions: the same change set, restricted to `ui/src/main.tsx`
  instead, produces 2 run (`ui:build`, `ui:test`) / 0 cached / 15
  unaffected — no Gradle module is even considered.
- Re-running that same core change end to end (`forge run`) showed
  `core:build` and `core:test` actually execute, but every downstream
  task (`cache:build`, `config:build`, `cli:build`,
  `control-plane:build`, `worker:build`, and their `:test` tasks)
  reports `restored from cache` rather than re-running — a comment-only
  source edit recompiles to a byte-identical class output, so
  `core:build`'s artifact digest is unchanged and nothing downstream's
  cache key moves. This is real evidence the cache is keyed on artifact
  content, not on "was an upstream task affected."
- "Can the same cached output be reused between local and CI
  environments?" — across two *isolated CI runs*, yes, and measured on
  PR #1: run
  [30670034348](https://github.com/amirr-k/forge-ci/actions/runs/30670034348)
  started from a cold cache ("Cache not found for input keys") and
  executed all 17 tasks in **1m25.1s**; the next push changed only
  `ui/src/main.tsx`, and run
  [30670257351](https://github.com/amirr-k/forge-ci/actions/runs/30670257351)
  restored that run's `.forge/cache` and reported 15 of 17 tasks
  `restored from cache`, executing only `ui:build` (3.3s) and `ui:test`
  (0.5s) — **4.4s** total. Different runner VM, different checkout, same
  artifacts.
- Between a *developer's machine* and CI, reuse is conditional, and the
  constraint is deliberate: `ToolchainFingerprint.current()`
  (`"Java " + Runtime.version()`) is a direct input to every cache key, so
  an entry only transfers to a machine whose `forge` CLI runs the
  identical JVM build — major *and* patch. CI pins `actions/setup-java` to
  Java 21, but that resolved to 21.0.11 while the local toolchain was
  21.0.12, so in practice these two caches do *not* interchange today.
  That is the cache being correct rather than convenient; loosening it
  would require proving the JVM patch level cannot affect compiled output.

Running the suite on a clean Linux runner — rather than only on the
developer machine that wrote it — also caught two tests that passed
locally for environment-specific reasons:

- `RestartSurvivalTest` boots a full Spring context with Testcontainers
  MySQL and MinIO but carried no `@Tag("integration")`, so it ran inside
  the deliberately Docker-free `test` task, and it never configured Redis
  — silently binding to whatever `localhost:6379` happened to be. On a
  machine with a leftover Compose Redis it passed; on CI it failed with
  `Unable to connect to Redis`. Now tagged (so it runs in
  `integrationTest`, where it is verified to genuinely pass, not merely
  be skipped) and pinned to `RedisTestContainer`.
- `DockerTaskExecutorTest` failed cleanup, not assertions: tasks run as
  root inside the container, so on Linux the files a task writes into the
  bind-mounted workspace are root-owned and JUnit's `@TempDir` teardown
  cannot delete them. Docker Desktop on macOS remaps ownership to the
  invoking user and hides this entirely. Fixed in the test — by emptying
  the directory from a root container before teardown — rather than by
  adding `--user` to `DockerTaskExecutor`, since production workers are
  themselves containerized and running tasks as root there is correct.

Both were latent before this phase; neither is specific to CI. Test
output also had to be made self-describing: `forge` invokes Gradle with
`-q`, which suppressed the failing test's name and left only `exit code
1`, so the root `build.gradle.kts` now configures `testLogging` for
failures and the workflow uploads JUnit XML on every run.

Self-hosting DispatchLab and Blackjack RL Lab (the phase's other two
required repositories) is deferred by product decision — this pass
applies ForgeCI only to itself; the sibling repos' `forgeci.yml`/CI-wrapper
integration and their two self-hosting questions (matching-engine-affected
DispatchLab benchmarks, C++-affected Blackjack native artifacts) are not
yet answered.

## Testing, CI/CD, and the production-quality audit (phase 9)

This phase was a gap-closing audit, not a rewrite: it built a coverage
matrix against the taxonomy in `spec/reference/quality-and-testing.md`,
implemented what was missing, wired the full required CI/CD pipeline, and
verified every production-quality rule against the code as it stands.

### Coverage matrix (taxonomy item → test)

**Unit**: graph construction (`TaskGraphTest`), cycle detection
(`CycleDetectorTest`), topological ordering (`TopologicalSorterTest`),
reverse closure (`AffectedTaskAnalyzerTest`), critical-path calculation
(`CriticalPathCalculatorTest`, new — nothing had exercised the scheduler's
tie-break weighting directly before), glob matching (`GlobMatcherTest`),
cache-key canonicalization (`CacheKeyCalculatorTest`), state transitions
(`StateMachineTest`), retry policy (`RetryPolicyTest`, new — backoff
growth and the attempt cap had only ever been observed indirectly through
integration tests).

**Property** (all new this phase): every emitted execution order respects
dependencies, and permuting how a graph is declared never changes the
selected build plan (`GraphPropertyTest`, 300 seeded random DAGs); the same
canonical inputs always produce the same cache key, and changing any one
declared contributor — name, command, outputs, timeout, toolchain, an
environment value, a dependency's digest, a source file's bytes — always
changes it (`CacheKeyPropertyTest`, 150 seeded scenarios); no accepted task
result ever transitions twice, under a generated storm of duplicate,
forged-lease, and contradictory reports against one real build over HTTP
(`ResultIdempotencePropertyTest`).

**Integration**: MySQL migrations and restart (`RestartSurvivalTest` boots
the real app twice against the same MySQL container), S3 upload/verify/
restore (`ArtifactControllerTest`), Kafka redelivery
(`KafkaTaskResultsIntegrationTest`), Redis TTL and restart
(`FailureRecoveryIntegrationTest#theSystemRecoversAfterARedisFlushDuringAnActiveBuild`),
control-plane/worker protocol (`WorkerSchedulingIntegrationTest`),
duplicate result handling (`FailureRecoveryIntegrationTest`,
`ResultIdempotencePropertyTest`).

**End-to-end**: local cold/warm build (`CacheCommandTest`), remote
incremental build (`RemoteIncrementalBuildEndToEndTest`, new — two
independent workspace directories sharing one real control plane over the
actual `HttpRemoteArtifactClient` wire protocol, not a fake stub; the only
place that protocol was previously exercised end to end rather than
through an in-memory `RemoteArtifactClient`), worker crash recovery
(`FailureRecoveryIntegrationTest`), guest public demo
(`DemoScenarioIntegrationTest`, `ui/e2e/primary-demo.spec.ts`),
self-hosted CI run (the `plan-and-run` job, phase 8).

**Concurrency** (all new this phase, in `ConcurrencyIntegrationTest`
unless noted): two builds in flight at once complete independently with no
task run executed twice; multiple workers claiming from the one global
queue never double-claim (also `WorkerSchedulingIntegrationTest`'s
existing pair); a result reported right at its own lease's expiry is
accepted, lease-rejected, or stale-rejected but never double-applied; a
late result after a *different* worker's lease reassignment is rejected
(`FailureRecoveryIntegrationTest`); several clients committing identical
artifact bytes at once produce exactly one artifact row — this test found
a real race, see below.

**Security** (the category with no earlier dedicated phase; all new this
phase): path traversal in artifacts (`TaskArchiveTest` plus the new
`ProjectFilesSecurityTest`, which found a real gap, see below);
command-array validation (`CommandExecutionSecurityTest`,
`DockerTaskExecutorTest#aCommandIsHandedToTheContainerAsArgvSoNothingInItIsShellSyntax`,
`ApiSecurityIntegrationTest#aSubmittedCommandIsStoredAsTheExactArgvArrayItArrivedAs`);
public endpoint rate limiting and guest command-submission attempts
(`PublicDemoSecurityIntegrationTest`); output-size enforcement
(`CommandExecutionSecurityTest`, `DockerTaskExecutorTest`); timeout
enforcement (`ProcessTaskRunnerTest`, `DockerTaskExecutorTest`); container
cleanup (`DockerTaskExecutorTest#noContainerSurvivesATaskThatWasKilledForRunningTooLong`
and its completed-normally counterpart).

**Failure recovery** (phase 6, re-verified here): all seven required
scenarios remain covered by `FailureRecoveryIntegrationTest`.

### Two real bugs this phase's tests found and fixed

- **Symlinks were followed out of the project directory.**
  `ProjectFiles.matching` walked with `Files.isRegularFile` (which follows
  symlinks), so a symlink inside a project pointing anywhere on the host
  filesystem could be selected by a declared glob, hashed into a cache key,
  and archived into a shared artifact — a real path-traversal-adjacent
  leak, not a hypothetical one. `ProjectFilesSecurityTest` (a new
  same-directory symlink case) caught it; fixed with
  `Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)` in
  `libs/cache/src/main/java/dev/forgeci/cache/ProjectFiles.java`.
- **Concurrent uploads of identical bytes raced past the dedupe check.**
  `RemoteArtifactService.commit` looks up an existing `Artifact` by digest
  before inserting a new one; two callers uploading the same bytes at the
  same time could both pass that check before either had inserted, so the
  loser's insert hit `uq_artifacts_digest` and surfaced as a raw
  `500` — worse, Hibernate refuses any further statement on a session
  after a flush-time constraint violation ("don't flush the Session after
  an exception occurs"), so a plain try/catch-and-refetch in the same
  transaction couldn't recover either.
  `ConcurrencyIntegrationTest#severalClientsCommittingTheSameBytesAtOnceProduceExactlyOneArtifact`
  (six concurrent uploaders) caught it; fixed by running the insert in its
  own `REQUIRES_NEW` transaction (the same pattern `SchedulerService`
  already used for losing lease-claim candidates) so a duplicate-key
  failure can fall back to a clean `findByDigest` read afterward. While
  investigating the related lease-expiry race, `SchedulerService`'s
  reclaim path was also tightened to clear a task run's lease token when
  reclaiming it — previously a late report against a reclaimed-but-not-
  yet-reassigned task run could still coincidentally match the stale lease
  fields and fail only later, and less informatively, on the state
  machine's `RETRY_WAIT -> SUCCEEDED` invalid-transition check.

### CI/CD pipeline

`.github/workflows/forgeci.yml` now runs, on every pull request against
`main`, alongside the existing self-hosting `plan-and-run` job (which
proves ForgeCI works on itself but — by design — only checks what its own
affected-task selection decides to run, not a substitute for an
unconditional gate):

- **`required-checks`**: `./gradlew check` — which, via the root
  `build.gradle.kts`'s `subprojects` block, already wires Spotless
  (`spotlessCheck`, Google Java Format's AOSP variant — 4-space indent,
  matching this codebase, rather than Google's 2-space default) and
  SpotBugs (`spotbugsMain`; disabled for test sources) into `check`
  alongside every module's `test` task and `apps/control-plane`'s
  `integrationTest` — covers Java formatting, static analysis, core
  unit/property tests, CLI tests, control-plane tests, the Testcontainers
  suite, and migration validation (real Flyway auto-migration against a
  real MySQL container, exercised by `RestartSurvivalTest` and every other
  `ControlPlaneIntegrationTest` subclass) in one command. Frontend
  lint/type checks and unit tests (`npm run lint` / `typecheck` / `test`)
  run alongside.
- **`container-images`**: both Dockerfiles actually build from a clean
  checkout.
- **`dependency-security-scan`**: `aquasecurity/trivy-action` filesystem
  scan (Gradle and npm lockfiles) at `HIGH,CRITICAL` severity, failing the
  job on a hit — chosen over an OWASP-dependency-check/NVD-API-key setup
  because it needs no secret and no rate-limited external service to be
  reliable in CI.
- **`browser-smoke-test`**: brings up the real `deploy/compose.yaml`
  stack, serves the actual UI against it, and runs
  `ui/e2e/primary-demo.spec.ts` with a real browser (Playwright/Chromium)
  — the literal "browser smoke test" the spec requires, not a unit test
  standing in for one.

`.github/workflows/release.yml` (push to `main`) wires the nine-step
default-branch release sequence: run all required checks; build versioned
CLI/control-plane/worker artifacts (versioned by commit SHA — never a
hand-maintained number that can drift from what actually shipped); build
immutable (SHA-tagged, never a mutable tag) container images; apply
migrations (Spring Boot/Flyway's own startup-time auto-migration against
the target MySQL — there is no separate migration-only step because
deploying the control plane *is* how migrations apply, both here and
against phase 10's eventual persistent target); deploy the control plane;
deploy both workers; explicitly verify MySQL/S3/Kafka/Redis connectivity
via each service's own Docker healthcheck status (not merely inferred from
the previous steps not erroring); run the fixed `no-change` public demo
scenario end to end and poll both builds to `SUCCEEDED`; and verify the
deployed instance's new `GET /api/version` endpoint echoes back the exact
commit SHA this release just deployed (`HealthController`, backed by a
`FORGE_GIT_COMMIT` environment variable the pipeline sets — never derived
from a `.git` directory, which a deployed jar doesn't have). This phase
wires that sequence against the same ephemeral Compose stack
`browser-smoke-test` already exercises, per the phase's explicit scope —
phase 10 swaps in a real, persistent target without changing steps 1-9
themselves.

### Production-quality-requirements audit

Every line of
[quality-and-testing.md's blanket rules](../../spec/reference/quality-and-testing.md#production-quality-requirements-blanket-rules-all-phases),
checked against the code as it stands today, not assumed:

- **No fabricated metrics.** `BuildMetrics`/`DemoScenarioService` measure
  real wall-clock time from real database timestamps; no benchmark numbers
  are published yet (that is phase 11's job), so none exist to fabricate.
- **No arbitrary public code execution.** `DemoScenario.fromScriptId`
  only ever accepts the fixed six-entry enum; `DemoBuildRequest` has no
  command/path/image field for a guest to set, and extra JSON fields bind
  to nothing (`PublicDemoSecurityIntegrationTest`).
- **No hidden shell interpolation.** `ProcessTaskRunner` and
  `DockerTaskExecutor` both invoke via argv arrays (`ProcessBuilder`,
  `docker run ... <command...>`), never `sh -c` string concatenation of
  task-declared content (`CommandExecutionSecurityTest`,
  `DockerTaskExecutorTest`).
- **No cache key based on timestamps or unstable ordering.**
  `CacheKeyCalculator` sorts every map/list before hashing;
  `TaskArchive.write` strips filesystem timestamps entirely, keeping only
  the executable bit; `CacheKeyPropertyTest` re-verifies permutation
  invariance over 150 generated cases, re-confirming phase 2's contributor
  list rather than assuming it still holds.
- **No partial artifact exposed as a hit.** Every read path
  (`RemoteArtifactService.fetchAndVerify`, `TaskCache.localLookup`)
  re-verifies digest and size before returning bytes
  (`ArtifactControllerTest`, `TaskCacheTest`).
- **No unbounded worker or guest concurrency.** `Worker.maxConcurrency`
  bounds `SchedulerService.claim`; `DemoGuestGuard` bounds guest workers to
  2 and serializes guest builds behind one global slot with per-client
  rate limiting (`PublicDemoSecurityIntegrationTest`).
- **No unbounded logs or artifact size.** `ProcessTaskRunner` and
  `DockerTaskExecutor` both cap captured output at 1 MiB with a per-line
  cap, verified to actually truncate rather than merely being configured
  to (`CommandExecutionSecurityTest`, `DockerTaskExecutorTest`).
- **No secrets in logs.** Both task runners start from an empty
  environment and only pass through a task's declared allowlist (plus
  `PATH`/`HOME`/`TMPDIR`/`LANG`) — an undeclared variable never reaches a
  task's process, so it can never end up in that task's own output either
  (`CommandExecutionSecurityTest#aTaskSeesOnlyTheEnvironmentItDeclares`).
- **No Redis-only authoritative state.** `RedisKeys` holds only TTL'd
  heartbeat/lease keys; MySQL's `lease_expiration` sweep runs
  unconditionally regardless of Redis's state
  (`FailureRecoveryIntegrationTest#theSystemRecoversAfterARedisFlushDuringAnActiveBuild`).
- **No exactly-once claim.** None made anywhere in code or docs;
  `ResultIdempotencePropertyTest` and `SchedulerService.reportResult`'s own
  documentation frame the guarantee as idempotent acceptance, explicitly
  not exactly-once delivery.
- **No stale worker result overwriting an accepted result.**
  `SchedulerService.reportResult`'s lease/attempt matching plus terminal-
  state check (`FailureRecoveryIntegrationTest`,
  `ConcurrencyIntegrationTest`'s lease-race case).
- **No unnecessary microservices.** Still exactly `cli`/`control-plane`/
  `worker`; nothing added this phase.
- **No fake public animation.** `DemoScenarioService.startBuild` schedules
  two genuinely concurrent, separately tracked builds; the Playwright spec
  asserts the result reflects a real running build, not a canned one.
- **All commands work from a clean clone.** Every `required-checks` and
  `release` CI job starts from `actions/checkout` with no pre-existing
  local state, which exercises this in practice; not independently
  re-verified as its own standalone clean-clone smoke test in this phase —
  that literal check is phase 13's final acceptance gate.
- **Local mode remains usable if remote infrastructure is unavailable.**
  `RemoteCacheConfig.fromEnvironment()` only activates remote mode when
  `FORGE_CONTROL_PLANE_URL` is set; an unreachable or unconfigured remote
  degrades to exactly local-only behavior, never a failure
  (`RemoteTaskCacheTest`).
- **Every public metric is reproducible.** Now closed: every published
  figure is generated by `benchmarks/scripts/write-report.py` from the
  committed per-trial data in `benchmarks/results/latest.json`, so a
  number in the docs cannot drift from its evidence. See
  `docs/benchmarks.md` for the commands and `docs/evidence.md` for what
  was *not* measured.

Two items above (clean-clone and public-metric reproducibility) are noted
as not independently closed by this phase's own tests — both are
explicitly gated on later phases (10 and 11) by the spec, and the audit
above says so rather than marking them done on inference.

## Deployment profiles and the published demo (phases 10-12)

There is no continuously hosted backend. Running the distributed system is something you do on
demand; the only permanently public artifact is a static page.

| Profile | What it is | Storage | Lifetime |
|---|---|---|---|
| `deploy/compose.yaml` | the normal two-worker development stack | MinIO | on demand |
| `deploy/local-benchmark/` | the same stack with the worker service scaled for worker-count comparisons | MinIO | on demand |
| `deploy/aws-reference/` | one EC2 host against real Amazon S3, for the official benchmark | Amazon S3 | minutes, then destroyed |

`aws-reference` is temporary by construction: `terraform destroy` is the only teardown path, the
root volume is `delete_on_termination`, the bucket is `force_destroy` with a one-day lifecycle
rule, and the public IPv4 is auto-assigned rather than an allocated Elastic IP. Its wrapper script
runs `destroy` and `audit` even when an earlier step fails, and `audit` exits non-zero if any
billable tagged resource survives. Details in `deploy/aws-reference/README.md`.

### Execution traces

`demo/traces/trace.schema.json` (v1) is one versioned contract shared by benchmark evidence and
the public demo: DAG nodes and edges, changed files, per-task status/reason/duration/executor,
ordered events with millisecond offsets, totals, and the baseline the run is compared against.

Traces are recordings of real `forge run` executions, produced by
`benchmarks/scripts/export-traces.py`. `benchmarks/scripts/validate-traces.mjs` enforces the schema
*and* cross-checks each trace against itself — that the totals match the task list, that every edge
references a real node, and that every task is a graph node. It runs in CI and again before the
demo is published, so a trace that contradicts itself cannot reach the page.

### The static demo

`ui/` builds in two modes. With `VITE_STATIC_DEMO=true` the landing route becomes the trace-replay
showcase, the API-backed routes are dropped from the router, and the result is a fully static
bundle: no control plane, no cloud call, no visitor-supplied code. It is published to GitHub Pages
from `.github/workflows/pages.yml`.

Because Pages serves from `/<repo>/`, the router takes its `basename` from `import.meta.env.BASE_URL`.
A build served under a prefix but routed at `/` renders an empty page that still returns HTTP 200
with the correct title, so `ui/e2e/static-demo.spec.ts` checks the rendered DOM at the real base
path — including that the page still works with every external request blocked — and runs both in
CI and against the built artifact before deployment.

## The `./forge` launcher

`./forge` is a POSIX shell script at the repository root, not a packaged
binary. It resolves a Java 21+ runtime (from `JAVA_HOME`, `PATH`,
`/usr/libexec/java_home`, or conventional install locations), runs
`./gradlew :apps:cli:installDist` if the CLI has not been built yet, then
execs the generated launcher. It does not change directory, so the project
being planned is whatever directory you are standing in.

## Exit codes

`0` success; `1` the build ran and a task failed, timed out, or was skipped
behind a failure; `2` ForgeCI could not run at all — invalid `forgeci.yml`,
a cyclic graph, no repository, bad usage. Expected failures print one
actionable message and no stack trace.
