# CNBA

A distributed incremental build system. It reads a repository's task
dependency graph, works out which tasks a change can actually affect, reuses
verified cached output for everything else, and runs what remains
concurrently — on local threads, or across Docker workers coordinated by a
control plane.

Change one file, rebuild only what that change affects.

```
$ cnba plan                       # after editing one service

Changed files
  services/catalog/src/main/java/CatalogService.java

Affected tasks
  catalog:build            RUN      source changed
  catalog:test             RUN      source changed
  orders:build             RUN      catalog:build output may change
  search:build             RUN      catalog:build output may change
  storefront:build         RUN      catalog:build output may change
  ...

Plan: 12 run, 0 cached, 13 unaffected
```

## Why

Most CI pipelines rebuild everything on every commit, because "everything"
is the only answer that is always correct. Doing better means answering two
different questions precisely:

1. **What could this change affect?** A graph question — cheap, structural,
   and safe to over-approximate.
2. **Did anything actually change?** A content question — exact, expensive,
   and unsafe to guess at.

CNBA keeps these separate. Affected-task selection walks reverse
dependencies and deliberately over-selects; cache keys then decide, per
task, whether the work is genuinely new. A task can be selected and *still*
be reused — which is what happens when an upstream task reran but produced
byte-identical output.

## Quick start

Requires Java 21+ and Git. Nothing else — no control plane, no Docker, no
database.

```bash
./gradlew test

cd demo/sample-monorepo
../../cnba doctor        # check Java, Git, repository, configuration
../../cnba plan          # what would run, and why
../../cnba run           # run it
```

`./cnba` is a launcher script: it resolves a Java 21+ runtime, builds the
CLI with `./gradlew :apps:cli:installDist` on first use, then runs it in your
current directory. Put the repository root on your `PATH` to type `cnba`
instead of `../../cnba`.

## The bundled demo

`demo/sample-monorepo/` is a 25-task Java project across 11 modules. Every
task really runs `javac`, loads the compiled classes to verify them,
packages a jar, and hashes it — so a change's blast radius is a real
measurement, not a simulation.

```bash
cd demo/sample-monorepo

../../cnba plan                  # clean tree: 0 run, 25 unaffected

../../cnba run --all             # cold build, nothing to reuse
../../cnba run --all             # again: 25 succeeded, all restored, 0.1s
../../cnba explain shared:build  # the cache key and every contributor to it
```

Three changes with visibly different blast radii:

```bash
# a leaf module: 3 tasks
echo "// tweak" >> services/accounts/src/main/java/AccountService.java
../../cnba plan          # 3 run, 22 unaffected

# a mid-graph module: 12 tasks, and orders/search/storefront come with it
echo "// tweak" >> services/catalog/src/main/java/CatalogService.java
../../cnba plan          # 12 run, 13 unaffected

# the shared core: everything
echo "// tweak" >> services/shared/src/main/java/Money.java
../../cnba plan          # 25 run, 0 unaffected
```

And a failure stops only what depends on it:

```bash
echo "// BROKEN" >> services/pricing/src/main/java/PriceCalculator.java
../../cnba run
#   pricing:test             FAILED         exit code 1
#   pricing:build            SKIPPED        dependency pricing:test failed
#   checkout:integration     SKIPPED        dependency pricing:build was skipped
#   ...
#   Run: 0 succeeded, 1 failed, 4 skipped
```

Independent branches of the graph keep running; everything downstream of the
failure is skipped rather than attempted. Reset with `git checkout -- .`
after any of these.

## Design decisions

**Cache keys are content-addressed and relocatable.** A task's key is a
canonical hash of its declaration, command, allowlisted environment values,
the sorted digest of every file matching a declared input glob, the
*artifact digest* of each direct dependency, and a toolchain fingerprint.
Absolute paths, timestamps, and random identifiers are never inputs, so the
same checkout in a different directory — or on a different machine — yields
the same key.

**Dependencies contribute digests, not keys.** This is the difference
between "an upstream task was selected" and "an upstream task produced
different bytes". A comment-only edit recompiles to identical class files,
so nothing downstream of it moves at all.

**A cache hit is verified, never assumed.** A hit requires a manifest *and*
a stored object whose bytes still match the recorded digest and size. A
manifest alone is not enough; a corrupted object is rejected and rebuilt.
Restoring an archive rejects any entry whose path would resolve outside the
project directory.

**Tasks are argv, never shell strings.** `cnba.yml` commands are argument
lists handed straight to `ProcessBuilder` or to `docker run`, so nothing in
a config file can be reinterpreted as shell syntax. Tasks start from an
empty environment and receive only `PATH`, `HOME`, `TMPDIR`, `LANG`, and
their declared allowlist — a task's result depends on what it declares.

**No exactly-once execution claim.** A task can genuinely run twice: a retry
after a crash, or two racing attempts under speculation. The guarantee is
*idempotent acceptance* — exactly one result is ever accepted per task run,
enforced by a single conditional `UPDATE ... WHERE winning_attempt_number IS
NULL`. Whichever attempt wins that row applies its result; every other one
is rejected, including a stalled worker that resumes and reports late.

**Each store is authoritative for exactly one thing.** MySQL owns accepted
build and task state. S3 owns artifact bytes. Redis only *accelerates*
detection — every lease and heartbeat sweep runs unconditionally against
MySQL regardless of Redis's state, and Redis keys are periodically re-armed
from the database, so a Redis flush costs latency and nothing else. Kafka is
event delivery, never a source of truth.

**Local mode never depends on the rest.** `libs/core` has no framework
dependency at all. The CLI's full local mode works with no network. Remote
caching activates only when `CNBA_CONTROL_PLANE_URL` is set, and a
configured-but-unreachable control plane degrades to local-only rather than
failing the command.

## Distributed mode

```bash
docker compose -f deploy/compose.yaml up --build
```

That brings up MySQL, Kafka, Redis, MinIO, the Spring Boot control plane,
and two Docker workers.

The control plane tracks state and hands out work; it never executes a task
itself. `Build` and `TaskRun` each have a state machine that is the only
path to mutating state — it validates against a fixed allowed-edges table,
rejects stale writes via optimistic locking, and emits exactly one ordered
`BuildEvent` per accepted transition, with the owning build row locked so
event sequence numbers stay gap-free under concurrency.

Workers register, heartbeat, and claim leases from one global ready queue,
running each task in its own container. Two scheduling policies ship: FIFO,
and a duration-aware critical-path policy that weights ready tasks by the
longest remaining chain beneath them.

**Artifact storage runs on Amazon S3**, with any S3-compatible endpoint
(MinIO locally) as a drop-in. Uploads are verified server-side rather than
trusted: the control plane recomputes the digest itself, writes to a temp
key, verifies what landed, then copies to its final content-addressed key
and records the cache entry transactionally. Any mismatch leaves nothing
behind, and a lookup re-verifies digest and size before returning bytes —
an object that no longer matches is reported as `409 artifact_corrupt`,
never as a hit. `deploy/aws-reference/` provisions a disposable EC2 host
against real S3 via Terraform, with teardown and a billable-resource audit
that run even when an earlier step fails.

**Failure recovery** does not wait on a task's own timeout. When a worker
misses heartbeats, every attempt it was holding is failed immediately rather
than left to expire on a lease deadline derived from the task's declared
timeout. Reclaimed attempts also skip the exponential retry backoff a
genuinely failing task gets — backoff exists to stop hammering bad work, and
a reclaimed lease is evidence the worker disappeared, not that the work was
wrong.

**Speculative execution** handles stragglers rather than failures. When a
worker finds no claimable work, the scheduler looks for a running attempt
that has outlived a multiple of its historical median and starts a second,
independent attempt on that idle worker. Because it only fires after a
worker has already found nothing unstarted to do, it can never take a slot
real work would have used. It is off by default — the trade is duplicate
compute for tail latency, which is only worth making on a cluster with idle
capacity.

## Measured results

Full methodology, per-trial data, and the cases where CNBA *doesn't*
help are in [docs/benchmarks.md](docs/benchmarks.md). Every published figure
is regenerated from committed raw trial data by
`benchmarks/scripts/write-report.py`.

| | |
|---|---|
| Incremental build vs cold build (single-module change) | **4.3× faster**, 76.8% reduction |
| Warm cache, no changes, 25 tasks | **270 ms** |
| Worker killed mid-build, 50 trials | **50/50 recovered**, p50 10.4 s, p95 11.9 s |
| Straggler mitigation, speculation on vs off | **p95 −44.9%**, at one duplicate execution |
| Duration-aware vs FIFO scheduling, 150-task graph | ~6% median improvement (directional) |

CNBA also builds itself. Its own `cnba.yml` describes the Gradle
multi-module graph, and `.github/workflows/cnba.yml` runs
`cnba plan`/`cnba run` against every pull request's merge-base, persisting
`.cnba/cache` across runs. On one PR: a cold run executed all 17 tasks in
**1m25s**; the next push changed only `ui/src/main.tsx`, reused 15 of 17
tasks from the previous run's cache, and finished in **4.4s** — different
runner VM, different checkout, same artifacts.

Cache entries transfer between machines only when the JVM build matches to
the patch level, because the toolchain fingerprint is a direct key input.
That is deliberate: loosening it would mean proving a JVM patch release
cannot affect compiled output.

## Configuration

`cnba.yml` at the project root declares each task's inputs, outputs,
dependencies, command, environment allowlist, and timeout:

```yaml
version: 1
project:
  name: sample-monorepo
defaults:
  timeout: 2m
  cacheable: true
tasks:
  catalog:build:
    environment: ["JAVA_HOME"]
    depends_on: ["shared:build"]
    inputs: ["services/catalog/**", "toolchain.lock"]
    outputs: ["build/catalog/**"]
    command: ["./scripts/task", "build", "catalog"]
```

Unknown fields are rejected with a file location rather than ignored, and
cycles are detected before anything runs. `cnba init` writes a commented
starting point and never overwrites an existing file. A change to
`cnba.yml` itself selects every task — the file can alter any command,
input, or edge, so over-invalidating is the only safe answer.

## Commands

| | |
|---|---|
| `cnba init` | write a starting `cnba.yml` |
| `cnba doctor` | check Java, Git, repository, and configuration |
| `cnba plan` | show which tasks the current changes affect, and why |
| `cnba run` | run them, with `-j N` concurrency and `--all` to force everything |
| `cnba explain <task>` | show a task's cache key, every contributor, and what changed |

Exit codes: `0` success; `1` the build ran and a task failed, timed out, or
was skipped behind a failure; `2` CNBA could not run at all — invalid
config, cyclic graph, no repository, bad usage. Expected failures print one
actionable message and no stack trace.

## Layout

```text
apps/cli            picocli entry point; complete local mode
apps/control-plane  Spring Boot: state machines, scheduler, S3, Kafka, Redis
apps/worker         Docker-executing worker: register, heartbeat, claim, run, report
libs/core           graph, change analysis, planning, local execution — no Spring
libs/config         cnba.yml parsing and strict validation
libs/cache          cache keys, deterministic archives, content-addressed storage
libs/protocol       worker <-> control-plane records, shared verbatim by both
demo/               bundled sample monorepo, a 150-task scale fixture, traces
benchmarks/         harnesses, raw per-trial results, report generators
deploy/             compose stack, benchmark profile, disposable AWS reference
ui/                 Vite + React demo: live dependency graph and build streams
```

## Stack

Java 21, Spring Boot, MySQL (Flyway), Redis, Kafka, Amazon S3, Docker,
Gradle, Terraform, React/TypeScript. Tested with JUnit, property-based
tests over generated DAGs and cache-key scenarios, Testcontainers
integration suites against real MySQL/Kafka/Redis/MinIO, and Playwright.

More detail: [docs/architecture.md](docs/architecture.md) ·
[docs/benchmarks.md](docs/benchmarks.md)
