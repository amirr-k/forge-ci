# Architecture

What exists today. Sections are added as later capabilities land; nothing
here describes work that is not in the repository.

## Modules

```text
apps/cli            picocli entry point; complete local mode
libs/core           graph, change analysis, planning, local execution — no Spring
libs/config         forgeci.yml parsing and strict validation
libs/cache          cache-key computation, deterministic archives, local content-addressed storage
libs/test-support   fixtures shared by other modules' tests
demo/sample-monorepo  bundled demo project used by the walkthrough in the README
```

`apps/control-plane`, `apps/worker`, and `libs/protocol` are declared in
`settings.gradle.kts` but empty; they are filled in by later phases.
`libs/core` deliberately has no framework dependency so planning and
execution stay usable from the CLI alone. `libs/cache` depends only on
`libs/core` — the same cache-key algorithm and artifact protocol will be
reused by the control plane and workers once remote execution lands.

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
