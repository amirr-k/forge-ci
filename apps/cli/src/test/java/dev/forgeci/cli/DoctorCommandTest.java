package dev.forgeci.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class DoctorCommandTest {

    @Test
    void passesEveryCheckInAValidProject(@TempDir Path directory) {
        try (CliFixture fixture = CliFixture.withCommittedProject(directory)) {
            CliFixture.Result result = fixture.run("doctor");

            assertEquals(ExitCode.SUCCESS, result.exitCode(), result.out());
            assertTrue(result.out().contains("java           OK"), result.out());
            assertTrue(result.out().contains("git            OK"), result.out());
            assertTrue(result.out().contains("repository     OK"), result.out());
            assertTrue(result.out().contains("configuration  OK"), result.out());
            assertTrue(result.out().contains("All 4 checks passed."), result.out());
        }
    }

    @Test
    void reportsWhatIsMissingWithoutFailingTheWholeCommand(@TempDir Path directory) {
        try (CliFixture fixture = new CliFixture(directory)) {
            CliFixture.Result result = fixture.run("doctor");

            assertEquals(ExitCode.USER_ERROR, result.exitCode());
            // Java and Git are fine here; only the repository and configuration checks can fail
            assertTrue(result.out().contains("java           OK"), result.out());
            assertTrue(result.out().contains("repository     FAIL"), result.out());
            assertTrue(result.out().contains("configuration  FAIL"), result.out());
            assertTrue(result.out().contains("Failed checks: repository, configuration"), result.out());
        }
    }
}
