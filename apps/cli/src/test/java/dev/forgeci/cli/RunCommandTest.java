package dev.forgeci.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.testsupport.GitTestRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class RunCommandTest {

    @Test
    void runsTheAffectedTasksAndStreamsTheirOutput(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = CliFixture.withCommittedProject(directory)) {
            Files.writeString(
                    directory.resolve("services/accounts/AccountService.java"), "edited\n");

            CliFixture.Result result = fixture.run("run");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertTrue(result.out().contains("[accounts:test] testing accounts"), result.out());
            assertTrue(result.out().contains("accounts:test            SUCCEEDED"), result.out());
            assertTrue(
                    result.out().contains("Run: 1 succeeded, 0 failed, 0 skipped"), result.out());
            assertFalse(result.out().contains("pricing:test"), result.out());
        }
    }

    @Test
    void haltsDownstreamTasksWhenADependencyFails(@TempDir Path directory) {
        GitTestRepository.initialize(directory)
                .write(
                        "forgeci.yml",
                        """
                        version: 1

                        project:
                          name: failing

                        tasks:
                          pricing:test:
                            inputs: ["services/pricing/**"]
                            command: ["sh", "-c", "echo 1 test failed >&2; exit 1"]

                          pricing:build:
                            depends_on: ["pricing:test"]
                            inputs: ["services/pricing/**"]
                            command: ["sh", "-c", "echo building pricing"]

                          checkout:integration:
                            depends_on: ["pricing:build"]
                            command: ["sh", "-c", "echo integrating checkout"]

                          accounts:test:
                            inputs: ["services/accounts/**"]
                            command: ["sh", "-c", "echo testing accounts"]
                        """)
                .write("services/pricing/PriceCalculator.java", "pricing\n")
                .write("services/accounts/AccountService.java", "accounts\n")
                .commitAll("init");

        try (CliFixture fixture = new CliFixture(directory)) {
            CliFixture.Result result = fixture.run("run", "--all");

            assertEquals(ExitCode.BUILD_FAILED, result.exitCode());
            assertTrue(result.out().contains("[pricing:test] 1 test failed"), result.out());
            assertTrue(
                    result.out().contains("pricing:build            SKIPPED"),
                    "a failed dependency must stop its downstream tasks:\n" + result.out());
            assertTrue(result.out().contains("dependency pricing:test failed"), result.out());
            assertTrue(result.out().contains("checkout:integration     SKIPPED"), result.out());
            // an independent task still runs, so one failure does not abandon the whole build
            assertTrue(result.out().contains("accounts:test            SUCCEEDED"), result.out());
            assertFalse(result.out().contains("building pricing"), result.out());
            assertTrue(
                    result.out().contains("Run: 1 succeeded, 1 failed, 2 skipped"), result.out());
        }
    }

    @Test
    void runsIndependentTasksConcurrently(@TempDir Path directory) {
        GitTestRepository.initialize(directory)
                .write(
                        "forgeci.yml",
                        """
                        version: 1

                        project:
                          name: concurrent

                        tasks:
                          slow:one:
                            command: ["sh", "-c", "sleep 1"]

                          slow:two:
                            command: ["sh", "-c", "sleep 1"]
                        """)
                .commitAll("init");

        try (CliFixture fixture = new CliFixture(directory)) {
            long startedAt = System.nanoTime();
            CliFixture.Result result = fixture.run("run", "--all", "--jobs", "2");
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertTrue(
                    elapsedMillis < 1_800,
                    "two independent 1s tasks should overlap, took " + elapsedMillis + "ms");
        }
    }

    @Test
    void reportsATimedOutTaskAsAFailure(@TempDir Path directory) {
        GitTestRepository.initialize(directory)
                .write(
                        "forgeci.yml",
                        """
                        version: 1

                        project:
                          name: slow

                        tasks:
                          slow:build:
                            timeout: 300ms
                            command: ["sh", "-c", "sleep 30"]
                        """)
                .commitAll("init");

        try (CliFixture fixture = new CliFixture(directory)) {
            CliFixture.Result result = fixture.run("run", "--all");

            assertEquals(ExitCode.BUILD_FAILED, result.exitCode());
            assertTrue(result.out().contains("slow:build               TIMED_OUT"), result.out());
            assertTrue(result.out().contains("timed out after 0.3s"), result.out());
        }
    }

    @Test
    void rejectsAnUnrepresentableTimeoutBeforeRunningAnything(@TempDir Path directory) {
        GitTestRepository.initialize(directory)
                .write(
                        "forgeci.yml",
                        """
                        version: 1

                        project:
                          name: overflowing

                        tasks:
                          slow:build:
                            timeout: 99999999999999999999s
                            command: ["sh", "-c", "true"]
                        """)
                .commitAll("init");

        try (CliFixture fixture = new CliFixture(directory)) {
            CliFixture.Result result = fixture.run("run", "--all");

            assertEquals(ExitCode.USER_ERROR, result.exitCode());
            assertTrue(result.err().contains("tasks.slow:build.timeout"), result.err());
            assertTrue(result.err().contains("is too long"), result.err());
            assertFalse(result.err().contains("\tat dev.forgeci"), result.err());
        }
    }

    @Test
    void doesNothingWhenNoTaskIsAffected(@TempDir Path directory) {
        try (CliFixture fixture = CliFixture.withCommittedProject(directory)) {
            CliFixture.Result result = fixture.run("run");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertTrue(result.out().contains("Nothing to run"), result.out());
        }
    }

    @Test
    void rejectsAnUnusableJobCount(@TempDir Path directory) {
        try (CliFixture fixture = CliFixture.withCommittedProject(directory)) {
            for (String jobs : new String[] {"-2", "0"}) {
                CliFixture.Result result = fixture.run("run", "--all", "--jobs", jobs);

                assertEquals(ExitCode.USER_ERROR, result.exitCode(), jobs);
                assertTrue(result.err().contains("--jobs must be at least 1"), result.err());
            }
        }
    }
}
