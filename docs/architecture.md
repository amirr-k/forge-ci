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
