# forge-ci

A distributed incremental build system: reads a repository's task
dependency graph, determines which tasks a change affects, and reuses
everything else. Change one file, rebuild only what that change affects.



## Quick start

```bash
./gradlew test

./gradlew :apps:cli:run --args="plan --changed services/pricing/File.java"
```

The second command runs against a bundled fixture graph and prints which
tasks are affected by a change under `services/pricing/`.
