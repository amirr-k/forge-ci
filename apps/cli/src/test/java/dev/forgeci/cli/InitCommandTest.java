package dev.forgeci.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class InitCommandTest {

    @Test
    void writesAConfigurationTheOtherCommandsCanRead(@TempDir Path directory) throws IOException {
        GitTestRepository.initialize(directory);
        try (CliFixture fixture = new CliFixture(directory)) {
            CliFixture.Result result = fixture.run("init");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.err());
            Path configFile = directory.resolve("forgeci.yml");
            assertTrue(Files.exists(configFile));
            assertTrue(Files.readString(configFile).contains("version: 1"));
            assertTrue(result.out().contains("Created " + configFile), result.out());

            // the generated file must be valid, not just present
            CliFixture.Result plan = fixture.run("plan", "--all");
            assertEquals(ExitCode.SUCCESS, plan.exitCode(), plan.err());
            assertTrue(plan.out().contains("example:test"), plan.out());
            assertTrue(plan.out().contains("Plan: 2 run, 0 cached, 0 unaffected"), plan.out());
        }
    }

    @Test
    void namesTheProjectAfterItsDirectory(@TempDir Path parent) throws IOException {
        Path directory = Files.createDirectories(parent.resolve("Sample Monorepo"));
        GitTestRepository.initialize(directory);
        try (CliFixture fixture = new CliFixture(directory)) {
            fixture.run("init");

            assertTrue(
                    Files.readString(directory.resolve("forgeci.yml"))
                            .contains("name: sample-monorepo"),
                    Files.readString(directory.resolve("forgeci.yml")));
        }
    }

    @Test
    void neverOverwritesAnExistingConfiguration(@TempDir Path directory) throws IOException {
        Path configFile = directory.resolve("forgeci.yml");
        Files.writeString(configFile, "# hand-written, do not clobber\n");
        try (CliFixture fixture = new CliFixture(directory)) {
            CliFixture.Result result = fixture.run("init");

            assertEquals(ExitCode.USER_ERROR, result.exitCode());
            assertTrue(result.err().contains("already exists"), result.err());
            assertTrue(result.err().contains("never overwrites"), result.err());
            assertEquals("# hand-written, do not clobber\n", Files.readString(configFile));
        }
    }
}
