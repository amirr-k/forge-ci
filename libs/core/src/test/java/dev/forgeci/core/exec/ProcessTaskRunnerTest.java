package dev.forgeci.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.core.model.TaskDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class ProcessTaskRunnerTest {

    private final List<String> output = Collections.synchronizedList(new ArrayList<>());
    private final ExecutionListener listener =
            new ExecutionListener() {
                @Override
                public void taskOutput(String task, String line) {
                    output.add(line);
                }
            };

    @Test
    void reportsSuccessAndStreamsBothOutputStreams(@TempDir Path directory) {
        TaskOutcome outcome =
                run(directory, task("demo:ok", List.of("sh", "-c", "echo to-stdout; echo to-stderr >&2")));

        assertEquals(TaskStatus.SUCCEEDED, outcome.status());
        assertEquals(0, outcome.exitCode());
        assertTrue(output.contains("to-stdout"), output.toString());
        assertTrue(output.contains("to-stderr"), output.toString());
    }

    @Test
    void reportsTheExitCodeOfAFailingCommand(@TempDir Path directory) {
        TaskOutcome outcome = run(directory, task("demo:fail", List.of("sh", "-c", "exit 3")));

        assertEquals(TaskStatus.FAILED, outcome.status());
        assertEquals(3, outcome.exitCode());
        assertEquals("exit code 3", outcome.detail());
    }

    @Test
    void runsInTheProjectDirectory(@TempDir Path directory) throws IOException {
        run(directory, task("demo:pwd", List.of("sh", "-c", "pwd")));

        assertEquals(List.of(directory.toRealPath().toString()), output);
    }

    @Test
    void reportsAMissingCommandAsAFailureNotAnException(@TempDir Path directory) {
        TaskOutcome outcome =
                run(directory, task("demo:missing", List.of("forge-no-such-command", "--now")));

        assertEquals(TaskStatus.FAILED, outcome.status());
        assertTrue(outcome.detail().startsWith("could not start 'forge-no-such-command'"), outcome.detail());
    }

    @Test
    void timesOutAndKillsTheWholeProcessTree(@TempDir Path directory) throws Exception {
        Path pidFile = directory.resolve("child.pid");
        TaskDefinition task =
                task(
                        "demo:hang",
                        List.of("sh", "-c", "sleep 60 & echo $! > child.pid; wait"));

        TaskOutcome outcome =
                new ProcessTaskRunner(directory).run(task, Duration.ofMillis(500), listener);

        assertEquals(TaskStatus.TIMED_OUT, outcome.status());
        assertTrue(outcome.detail().startsWith("timed out after"), outcome.detail());

        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        assertTrue(waitForExit(childPid), "child process " + childPid + " outlived its parent task");
    }

    private TaskOutcome run(Path directory, TaskDefinition task) {
        return new ProcessTaskRunner(directory).run(task, Duration.ofSeconds(30), listener);
    }

    private static TaskDefinition task(String name, List<String> command) {
        return new TaskDefinition(
                name, List.of(), List.of(), List.of(), command, List.of(), "1m", true);
    }

    private static boolean waitForExit(long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
