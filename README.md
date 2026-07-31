# forge-ci

A distributed incremental build system: reads a repository's task
dependency graph, determines which tasks a change affects, and reuses
everything else. Change one file, rebuild only what that change affects.

## Status

Local mode works: `forge init`, `forge plan`, `forge run`, `forge explain`,
and `forge doctor` run against a real repository with no other services —
no control plane, no Docker, no database. `forge plan` reads changed files
from Git and prints the affected-task closure; `forge run` executes it with
bounded concurrency, dependency ordering, per-task timeouts, and streamed
task-prefixed logs. A local content-addressed cache verifies and restores a
task's declared outputs instead of rerunning it whenever its inputs,
command, dependencies, and toolchain all still match a prior run; `forge
explain <task>` shows the cache key, its contributor breakdown, and why a
task ran or was reused.

A Spring Boot control plane (`apps/control-plane`) now tracks build/task
state in MySQL: project registration, plan submission, build creation, the
`Build`/`TaskRun` state machines with transactional transitions and
ordered `BuildEvent`s, build history with pagination, and health/readiness
endpoints. It does not execute anything yet — no worker registration, no
Docker execution, no remote artifact cache.

Not built yet: remote artifact cache, distributed workers, Docker
execution, and the public demo UI.

## Requirements

Java 21 or newer, and Git for local mode — `./forge` builds the CLI itself
on first use. The control plane additionally needs a MySQL instance (see
below); nothing else in local mode touches it.

## Control plane

```bash
docker run -d --name forgeci-mysql -e MYSQL_DATABASE=forgeci \
  -e MYSQL_USER=forgeci -e MYSQL_PASSWORD=forgeci \
  -e MYSQL_ROOT_PASSWORD=forgeci -p 3306:3306 mysql:8.0

FORGE_DB_URL=jdbc:mysql://localhost:3306/forgeci \
FORGE_DB_USER=forgeci FORGE_DB_PASSWORD=forgeci \
  ./gradlew :apps:control-plane:bootRun

curl -X POST localhost:8080/api/projects -H 'Content-Type: application/json' \
  -d '{"name":"dispatch-lab","repositoryIdentity":"git@example.com:example/dispatch-lab.git","defaultBranch":"main","configVersion":1}'
```

`./gradlew :apps:control-plane:test` runs the Testcontainers-backed suite
(needs a working Docker daemon) — migrations, state-transition validity,
idempotent submission, restart survival, and event-sequence ordering.

## Quick start

```bash
./gradlew test

cd demo/sample-monorepo
../../forge doctor        # check Java, Git, repository, configuration
../../forge plan          # what would run, and why
../../forge run           # run it
```

`./forge` is a launcher: it finds a Java 21+ runtime, builds the CLI with
`./gradlew :apps:cli:installDist` the first time, then runs it in your
current directory. Put the repository root on your `PATH` to type `forge`
instead of `../../forge`.

## Try the bundled demo

`demo/sample-monorepo/` is a nine-task project across seven modules. Each
task stands in for a compile or test step, so a change's blast radius is
visible immediately.

```bash
cd demo/sample-monorepo

# nothing changed: nothing to do
../../forge plan

# a full build, for a first run with nothing to reuse
../../forge run --all

# run it again: every task restores from cache instead of rerunning
../../forge run --all
../../forge explain shared:build

# a leaf-module change: one task
echo "// tweak" >> services/accounts/src/main/java/AccountService.java
../../forge plan
git checkout -- services/accounts

# a shared-core change: eight of nine tasks, accounts untouched
echo "// tweak" >> services/shared/src/main/java/Money.java
../../forge plan
../../forge run -j 4
git checkout -- services/shared

# a failing test stops everything downstream of it
echo "// BROKEN" >> services/pricing/src/main/java/PriceCalculator.java
../../forge run          # pricing:test FAILED, three tasks SKIPPED, exit code 1
git checkout -- services/pricing
```

## Configuration

`forgeci.yml` at the project root declares each task's inputs, outputs,
dependencies, command, and timeout. Commands are argument lists, never
shell strings, and unknown fields are rejected with a file location.
`forge init` writes a commented starting point and never overwrites an
existing file.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | the build ran and a task failed, timed out, or was skipped behind a failure |
| 2 | ForgeCI could not run: bad configuration, bad usage, missing prerequisite |

More detail: [docs/architecture.md](docs/architecture.md).
