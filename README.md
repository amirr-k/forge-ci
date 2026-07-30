# forge-ci

A distributed incremental build system: reads a repository's task
dependency graph, determines which tasks a change affects, and reuses
everything else. Change one file, rebuild only what that change affects.

## Status

Phase 0 (bootstrap): a Gradle multi-module skeleton with a working task
graph model (`libs/core`), a `forgeci.yml` parser and strict validator
(`libs/config`), and a CLI `plan` command (`apps/cli`) that prints the
affected-task closure for a statically supplied changed-file set. No
execution, caching, or remote infrastructure yet.

## Quick start

```bash
./gradlew test

./gradlew :apps:cli:run --args="plan --changed services/pricing/File.java"
```

The second command runs against a bundled fixture graph and prints which
tasks are affected by a change under `services/pricing/`.
