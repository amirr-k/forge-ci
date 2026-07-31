package dev.forgeci.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.core.model.TaskDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The execution-side security properties a task must not be able to break: a declared command is an
 * argv array run directly, never a shell string, so nothing in {@code forgeci.yml} can be
 * interpolated; a task sees only the environment it declares; and captured output is bounded no
 * matter how much the task writes.
 */
@DisabledOnOs(OS.WINDOWS)
class CommandExecutionSecurityTest {

    @Test
    void shellMetacharactersInAnArgumentAreDataNotSyntax(@TempDir Path directory) {
        // if anything interpolated this, the output would be a user id or an error, not the text
        String injection = "$(id); `whoami`; rm -rf / | tee /tmp/pwned && echo done";
        List<String> output =
                run(directory, List.of("/bin/echo", injection), Duration.ofSeconds(30));

        assertEquals(List.of(injection), output);
    }

    @Test
    void aCommandIsNeverResolvedThroughAShellSoRedirectionCannotCreateFiles(@TempDir Path directory)
            throws IOException {
        Path target = directory.resolve("written-by-redirection.txt");

        run(
                directory,
                List.of("/bin/echo", "payload", ">", target.toString()),
                Duration.ofSeconds(30));

        assertFalse(
                Files.exists(target),
                "'>' must reach the program as a literal argument, not as a redirection");
    }

    @Test
    void aTaskSeesOnlyTheEnvironmentItDeclares(@TempDir Path directory) {
        // FORGE_TEST_SECRET is set for this JVM by the build, never declared by the task below
        List<String> output = run(directory, List.of("/usr/bin/env"), Duration.ofSeconds(30));

        assertTrue(
                output.stream().noneMatch(line -> line.startsWith("FORGE_TEST_SECRET=")),
                "undeclared variables must not leak into a task");
    }

    @Test
    void outputIsTruncatedOnceATaskExceedsTheCaptureBudget(@TempDir Path directory) {
        // ~1.5 MB across many lines, past the 1 MiB capture bound
        List<String> command =
                List.of(
                        "/bin/sh",
                        "-c",
                        "i=0; while [ $i -lt 50000 ]; do echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; i=$((i+1)); done");

        List<String> output = run(directory, command, Duration.ofMinutes(2));

        assertTrue(
                output.stream().anyMatch(line -> line.startsWith("[output truncated at")),
                "expected a truncation notice");
        long forwardedCharacters = output.stream().mapToLong(line -> line.length() + 1L).sum();
        assertTrue(
                forwardedCharacters < 2L * (1 << 20),
                "forwarded " + forwardedCharacters + " characters despite the bound");
    }

    private static List<String> run(Path directory, List<String> command, Duration timeout) {
        List<String> output = new CopyOnWriteArrayList<>();
        ExecutionListener listener =
                new ExecutionListener() {
                    @Override
                    public void taskOutput(String task, String line) {
                        output.add(line);
                    }
                };
        TaskDefinition task =
                new TaskDefinition(
                        "security:check",
                        List.of(),
                        List.of(),
                        List.of(),
                        command,
                        List.of(),
                        "2m",
                        true);
        new ProcessTaskRunner(directory).run(task, timeout, listener);
        return new ArrayList<>(output);
    }
}
