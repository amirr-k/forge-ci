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
class PlanCommandTest {

    @Test
    void selectsOnlyTheTasksALeafChangeAffects(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = CliFixture.withCommittedProject(directory)) {
            Files.writeString(
                    directory.resolve("services/accounts/AccountService.java"), "edited\n");

            CliFixture.Result result = fixture.run("plan");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertTrue(
                    result.out().contains("services/accounts/AccountService.java"), result.out());
            assertTrue(
                    result.out().contains("accounts:test            RUN      source changed"),
                    result.out());
            assertFalse(result.out().contains("pricing:test"), result.out());
            assertTrue(result.out().contains("Plan: 1 run, 0 cached, 3 unaffected"), result.out());
        }
    }

    @Test
    void followsTheGraphForASharedCoreChange(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = CliFixture.withCommittedProject(directory)) {
            Files.writeString(directory.resolve("services/shared/Money.java"), "edited\n");

            CliFixture.Result result = fixture.run("plan");

            assertTrue(
                    result.out().contains("shared:build             RUN      source changed"),
                    result.out());
            assertTrue(
                    result.out()
                            .contains(
                                    "pricing:test             RUN      shared:build output may change"),
                    result.out());
            assertTrue(
                    result.out()
                            .contains(
                                    "pricing:build            RUN      pricing:test output may change"),
                    result.out());
            assertTrue(result.out().contains("Plan: 3 run, 0 cached, 1 unaffected"), result.out());
        }
    }

    @Test
    void printsTheSpecifiedSectionsInOrder(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = CliFixture.withCommittedProject(directory)) {
            Files.writeString(
                    directory.resolve("services/pricing/PriceCalculator.java"), "edited\n");

            String out = fixture.run("plan").out();

            assertTrue(out.startsWith("ForgeCI plan\n\nChanged files\n"), out);
            assertTrue(out.indexOf("Changed files") < out.indexOf("Affected tasks"), out);
            assertTrue(out.indexOf("Affected tasks") < out.indexOf("Plan: "), out);
            // nothing may be reported as reused until there is a verified cache to reuse from
            assertFalse(out.contains("Reused tasks"), out);
            assertFalse(out.contains("CACHED\n"), out);
        }
    }

    @Test
    void reportsACleanTreeAsNothingToDo(@TempDir Path directory) {
        try (CliFixture fixture = CliFixture.withCommittedProject(directory)) {
            CliFixture.Result result = fixture.run("plan");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertTrue(result.out().contains("Changed files\n  (none)"), result.out());
            assertTrue(result.out().contains("Plan: 0 run, 0 cached, 4 unaffected"), result.out());
        }
    }

    @Test
    void selectsEverythingForAFullBuild(@TempDir Path directory) {
        try (CliFixture fixture = CliFixture.withCommittedProject(directory)) {
            CliFixture.Result result = fixture.run("plan", "--all");

            assertTrue(result.out().contains("Full build requested"), result.out());
            assertTrue(result.out().contains("Plan: 4 run, 0 cached, 0 unaffected"), result.out());
        }
    }

    @Test
    void comparesAgainstAnEarlierRevisionOnRequest(@TempDir Path directory) {
        try (CliFixture fixture = CliFixture.withCommittedProject(directory)) {
            fixture.repository()
                    .write("services/pricing/PriceCalculator.java", "edited\n")
                    .commitAll("edit pricing");

            CliFixture.Result result = fixture.run("plan", "--base", "HEAD~1");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            assertTrue(
                    result.out().contains("services/pricing/PriceCalculator.java"), result.out());
            assertTrue(result.out().contains("Plan: 2 run, 0 cached, 2 unaffected"), result.out());
        }
    }

    @Test
    void rebuildsEverythingWhenTheConfigurationChanges(@TempDir Path directory) throws IOException {
        try (CliFixture fixture = CliFixture.withCommittedProject(directory)) {
            Files.writeString(
                    directory.resolve("forgeci.yml"), CliFixture.DEMO_CONFIG.replace("1m", "2m"));

            CliFixture.Result result = fixture.run("plan");

            assertTrue(result.out().contains("forgeci.yml changed"), result.out());
            assertTrue(result.out().contains("Plan: 4 run, 0 cached, 0 unaffected"), result.out());
        }
    }

    @Test
    void explainsAMissingConfigurationWithoutAStackTrace(@TempDir Path directory) {
        GitTestRepository.initialize(directory).write("README.md", "hi\n").commitAll("init");
        try (CliFixture fixture = new CliFixture(directory)) {
            CliFixture.Result result = fixture.run("plan");

            assertEquals(ExitCode.USER_ERROR, result.exitCode());
            assertTrue(result.err().contains("no forgeci.yml in"), result.err());
            assertTrue(result.err().contains("forge init"), result.err());
            assertFalse(result.err().contains("\tat dev.forgeci"), result.err());
        }
    }

    @Test
    void explainsAnInvalidConfigurationWithItsLocation(@TempDir Path directory) {
        GitTestRepository.initialize(directory)
                .write(
                        "forgeci.yml",
                        "version: 1\nproject:\n  name: broken\ntasks:\n  a:build:\n    command: \"make all\"\n")
                .commitAll("init");
        try (CliFixture fixture = new CliFixture(directory)) {
            CliFixture.Result result = fixture.run("plan");

            assertEquals(ExitCode.USER_ERROR, result.exitCode());
            assertTrue(result.err().contains("tasks.a:build.command"), result.err());
            assertTrue(result.err().contains("must be a list of arguments"), result.err());
            assertFalse(result.err().contains("\tat dev.forgeci"), result.err());
        }
    }

    @Test
    void explainsADirectoryThatIsNotARepository(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("forgeci.yml"), CliFixture.DEMO_CONFIG);
        try (CliFixture fixture = new CliFixture(directory)) {
            CliFixture.Result result = fixture.run("plan");

            assertEquals(ExitCode.USER_ERROR, result.exitCode());
            assertTrue(result.err().contains("not inside a Git repository"), result.err());
        }
    }

    @Test
    void reportsACycleWithTheFullPath(@TempDir Path directory) {
        GitTestRepository.initialize(directory)
                .write(
                        "forgeci.yml",
                        """
                        version: 1

                        project:
                          name: cyclic

                        tasks:
                          frontend:build:
                            depends_on: ["api:generate"]
                            command: ["sh", "-c", "true"]

                          api:generate:
                            depends_on: ["frontend:build"]
                            command: ["sh", "-c", "true"]
                        """)
                .commitAll("init");
        try (CliFixture fixture = new CliFixture(directory)) {
            CliFixture.Result result = fixture.run("plan");

            assertEquals(ExitCode.USER_ERROR, result.exitCode());
            assertTrue(result.err().contains("Cycle detected:"), result.err());
            assertTrue(result.err().contains(" -> "), result.err());
        }
    }
}
