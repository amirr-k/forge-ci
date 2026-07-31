package dev.forgeci.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.testsupport.GitTestRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the local cache end to end through the CLI: cold build, warm build, incremental build,
 * corruption recovery, and {@code forge explain}. Each task's command appends one line to a marker
 * file every time it actually runs — the line count is how these tests tell "restored from cache"
 * apart from "ran again" without mocking the process layer.
 */
@DisabledOnOs(OS.WINDOWS)
class CacheCommandTest {

    private static final String CONFIG =
            """
            version: 1

            project:
              name: cache-fixture

            defaults:
              timeout: 1m

            tasks:
              shared:build:
                inputs:
                  - "services/shared/**"
                outputs:
                  - "build/shared/marker.txt"
                command: ["sh", "-c", "mkdir -p build/shared && echo run >> build/shared/marker.txt"]

              pricing:build:
                depends_on:
                  - "shared:build"
                inputs:
                  - "services/pricing/**"
                outputs:
                  - "build/pricing/marker.txt"
                command: ["sh", "-c", "mkdir -p build/pricing && echo run >> build/pricing/marker.txt"]

              accounts:test:
                inputs:
                  - "services/accounts/**"
                outputs:
                  - "build/accounts/marker.txt"
                command: ["sh", "-c", "mkdir -p build/accounts && echo run >> build/accounts/marker.txt"]
            """;

    @Test
    void coldBuildRunsEveryTaskAndCachesItsOutputs(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = fixture(directory)) {
            CliFixture.Result result = fixture.run("run", "--all");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertEquals(1, markerRuns(directory, "shared"));
            assertEquals(1, markerRuns(directory, "pricing"));
            assertEquals(1, markerRuns(directory, "accounts"));
            assertTrue(result.out().contains("Run: 3 succeeded"), result.out());
        }
    }

    @Test
    void warmBuildRestoresEveryTaskFromCacheWithoutReexecuting(@TempDir Path directory)
            throws IOException {
        try (CliFixture fixture = fixture(directory)) {
            fixture.run("run", "--all");

            CliFixture.Result result = fixture.run("run", "--all");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertEquals(
                    1,
                    markerRuns(directory, "shared"),
                    "a cache hit must not re-invoke the command");
            assertEquals(1, markerRuns(directory, "pricing"));
            assertEquals(1, markerRuns(directory, "accounts"));
            assertTrue(result.out().contains("restored from cache"), result.out());
        }
    }

    @Test
    void incrementalBuildRerunsOnlyTheAffectedSubset(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = fixture(directory)) {
            fixture.run("run", "--all");
            Files.writeString(
                    directory.resolve("services/pricing/PriceCalculator.java"), "edited\n");

            CliFixture.Result result = fixture.run("run");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertEquals(2, markerRuns(directory, "pricing"), "the changed task must re-run");
            assertEquals(1, markerRuns(directory, "shared"), "an unaffected task must not re-run");
            assertEquals(
                    1, markerRuns(directory, "accounts"), "an unaffected task must not re-run");
            assertFalse(result.out().contains("accounts:test"), result.out());
        }
    }

    @Test
    void aCorruptedArtifactForcesARebuildOfOnlyThatTask(@TempDir Path directory)
            throws IOException {
        try (CliFixture fixture = fixture(directory)) {
            fixture.run("run", "--all");
            corruptTheStoredObjectFor(directory, "pricing:build");

            CliFixture.Result result = fixture.run("run", "--all");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertEquals(
                    2,
                    markerRuns(directory, "pricing"),
                    "a corrupted artifact must trigger a rebuild");
            assertEquals(
                    1,
                    markerRuns(directory, "shared"),
                    "an untouched artifact must still be a hit");
        }
    }

    @Test
    void planReportsAWarmCacheAsReusedTasks(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = fixture(directory)) {
            fixture.run("run", "--all");

            CliFixture.Result result = fixture.run("plan", "--all");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertTrue(result.out().contains("Reused tasks"), result.out());
            assertTrue(result.out().contains("shared:build             CACHED"), result.out());
            assertTrue(result.out().contains("Plan: 0 run, 3 cached, 0 unaffected"), result.out());
        }
    }

    @Test
    void explainReportsAMissWithNoCacheEntryYet(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = fixture(directory)) {
            CliFixture.Result result = fixture.run("explain", "shared:build");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertTrue(result.out().contains("Cache key: sha256:"), result.out());
            assertTrue(result.out().contains("Contributors"), result.out());
            assertTrue(result.out().contains("task definition"), result.out());
            assertTrue(result.out().contains("source inputs"), result.out());
            assertTrue(result.out().contains("dependency artifacts"), result.out());
            assertTrue(result.out().contains("toolchain"), result.out());
            assertTrue(result.out().contains("Result: cache miss"), result.out());
            assertTrue(
                    result.out().contains("Reason: no cache entry for this task yet"),
                    result.out());
        }
    }

    @Test
    void explainReportsAHitAfterAWarmRun(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = fixture(directory)) {
            fixture.run("run", "--all");

            CliFixture.Result result = fixture.run("explain", "shared:build");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertTrue(result.out().contains("Result: cache hit"), result.out());
            assertFalse(result.out().contains("Reason:"), result.out());
        }
    }

    @Test
    void explainReportsAChangedSourceInputByName(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = fixture(directory)) {
            fixture.run("run", "--all");
            Files.writeString(directory.resolve("services/shared/Money.java"), "edited\n");

            CliFixture.Result result = fixture.run("explain", "shared:build");

            assertTrue(
                    result.out()
                            .contains("Reason: source input services/shared/Money.java changed"),
                    result.out());
        }
    }

    @Test
    void explainRejectsAnUnknownTask(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = fixture(directory)) {
            CliFixture.Result result = fixture.run("explain", "does:not-exist");

            assertEquals(ExitCode.USER_ERROR, result.exitCode());
            assertTrue(result.err().contains("no such task"), result.err());
        }
    }

    private static CliFixture fixture(Path directory) {
        GitTestRepository.initialize(directory)
                .write("forgeci.yml", CONFIG)
                .write("services/shared/Money.java", "shared\n")
                .write("services/pricing/PriceCalculator.java", "pricing\n")
                .write("services/accounts/AccountService.java", "accounts\n")
                .commitAll("bundle the cache fixture");
        return new CliFixture(directory);
    }

    private static int markerRuns(Path directory, String task) throws IOException {
        Path marker = directory.resolve("build").resolve(task).resolve("marker.txt");
        if (!Files.exists(marker)) {
            return 0;
        }
        return Files.readString(marker).split("\n").length;
    }

    /**
     * The task name isn't stored in the object itself, but each task's archive is the only one
     * containing its own output path — searching the (mostly-ASCII) archive bytes for that path
     * finds the right object without needing to know the digest up front.
     */
    private static void corruptTheStoredObjectFor(Path directory, String taskName)
            throws IOException {
        String marker = taskName.substring(0, taskName.indexOf(':'));
        Path objects = directory.resolve(".forge/cache/objects");
        try (Stream<Path> files = Files.walk(objects)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                byte[] content = Files.readAllBytes(file);
                if (new String(content, java.nio.charset.StandardCharsets.UTF_8).contains(marker)) {
                    Files.write(
                            file, "corrupted".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
    }
}
