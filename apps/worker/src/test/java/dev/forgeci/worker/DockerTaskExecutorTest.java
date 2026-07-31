package dev.forgeci.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.protocol.ClaimedTaskResponse;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises real {@code docker run} invocations — success, failure, timeout, and bounded output
 * capture — against the actual Docker daemon (needs Docker locally, same as the control plane's
 * Testcontainers-backed suites). No control plane involved: this validates the worker's own
 * execution mechanics in isolation.
 */
@Tag("docker")
class DockerTaskExecutorTest {

    @TempDir Path workspace;

    private WorkerConfig config;

    @BeforeEach
    void setUp() {
        config =
                new WorkerConfig(
                        URI.create("http://localhost:0"),
                        "test-worker",
                        List.of(),
                        1,
                        "test",
                        workspace,
                        null,
                        "alpine:3.20",
                        "1",
                        "256m",
                        null,
                        1000);
    }

    /**
     * Tasks run as root inside the container (production workers are themselves containerized), so
     * on Linux anything a task wrote into the bind-mounted workspace is root-owned and the test
     * JVM's user cannot delete it — JUnit's own {@code @TempDir} cleanup then fails the test.
     * Docker Desktop on macOS remaps ownership to the invoking user and hides this, which is why it
     * only ever failed on CI. Emptying the directory from a root container first leaves JUnit
     * nothing but the directory it already owns.
     */
    @AfterEach
    void emptyWorkspaceAsRoot() throws Exception {
        new ProcessBuilder(
                        "docker",
                        "run",
                        "--rm",
                        "-v",
                        workspace.toAbsolutePath() + ":/workspace",
                        "alpine:3.20",
                        "sh",
                        "-c",
                        "rm -rf /workspace/..?* /workspace/.[!.]* /workspace/*")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void aSuccessfulCommandReportsSuccessAndStreamsOutput() {
        ClaimedTaskResponse task = task(List.of("/bin/sh", "-c", "echo hello-from-container"), 30);
        List<String> lines = new CopyOnWriteArrayList<>();

        DockerTaskExecutor.ExecutionResult result =
                new DockerTaskExecutor(config).run(task, lines::addAll);

        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertTrue(lines.stream().anyMatch(l -> l.contains("hello-from-container")));
    }

    @Test
    void aFailingCommandReportsTheExitCode() {
        ClaimedTaskResponse task = task(List.of("/bin/sh", "-c", "exit 7"), 30);

        DockerTaskExecutor.ExecutionResult result =
                new DockerTaskExecutor(config).run(task, lines -> {});

        assertFalse(result.success());
        assertEquals(7, result.exitCode());
    }

    @Test
    void aTaskThatOutlivesItsTimeoutIsKilledAndReportedAsFailed() {
        ClaimedTaskResponse task = task(List.of("/bin/sh", "-c", "sleep 30"), 1);

        long startedAt = System.nanoTime();
        DockerTaskExecutor.ExecutionResult result =
                new DockerTaskExecutor(config).run(task, lines -> {});
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertFalse(result.success());
        assertTrue(result.failureReason() != null && result.failureReason().contains("timed out"));
        // proves the container was actually killed rather than the JVM just giving up on waiting
        assertTrue(
                elapsed.compareTo(Duration.ofSeconds(15)) < 0,
                "expected a fast kill, took " + elapsed);
    }

    @Test
    void anOutputFileWrittenIntoTheWorkspaceIsVisibleOnTheHostAfterward() throws Exception {
        ClaimedTaskResponse task =
                task(
                        List.of(
                                "/bin/sh",
                                "-c",
                                "mkdir -p build/x && echo built > build/x/out.txt"),
                        30);

        DockerTaskExecutor.ExecutionResult result =
                new DockerTaskExecutor(config).run(task, lines -> {});

        assertTrue(result.success());
        Path output = workspace.resolve("build/x/out.txt");
        assertTrue(Files.exists(output));
        assertTrue(Files.readString(output).contains("built"));
    }

    @Test
    void noContainerSurvivesATaskThatWasKilledForRunningTooLong() {
        ClaimedTaskResponse task = task(List.of("/bin/sh", "-c", "sleep 300"), 1, 8801);

        DockerTaskExecutor.ExecutionResult result =
                new DockerTaskExecutor(config).run(task, lines -> {});

        assertFalse(result.success());
        assertTrue(
                awaitContainerGone(containerNameOf(task)),
                "a killed task left its container behind");
    }

    @Test
    void noContainerSurvivesATaskThatFinishedOnItsOwn() {
        ClaimedTaskResponse task = task(List.of("/bin/sh", "-c", "echo done"), 30, 8802);

        assertTrue(new DockerTaskExecutor(config).run(task, lines -> {}).success());
        assertTrue(
                awaitContainerGone(containerNameOf(task)),
                "a completed task left its container behind");
    }

    @Test
    void aCommandIsHandedToTheContainerAsArgvSoNothingInItIsShellSyntax() {
        String injection = "$(id); `whoami` && rm -rf /";
        ClaimedTaskResponse task = task(List.of("/bin/echo", injection), 30, 8803);
        List<String> lines = new CopyOnWriteArrayList<>();

        assertTrue(new DockerTaskExecutor(config).run(task, lines::addAll).success());

        assertTrue(lines.contains(injection), "expected the literal argument, got " + lines);
    }

    @Test
    void outputIsTruncatedOnceATaskExceedsTheCaptureBudget() {
        // ~1.5 MB, past the 1 MiB the executor is willing to forward
        ClaimedTaskResponse task =
                task(
                        List.of(
                                "/bin/sh",
                                "-c",
                                "i=0; while [ $i -lt 50000 ]; do echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; i=$((i+1)); done"),
                        120,
                        8804);
        List<String> lines = new CopyOnWriteArrayList<>();

        assertTrue(new DockerTaskExecutor(config).run(task, lines::addAll).success());

        assertTrue(
                lines.stream().anyMatch(line -> line.startsWith("[output truncated at")),
                "expected a truncation notice");
        long forwarded = lines.stream().mapToLong(line -> line.length() + 1L).sum();
        assertTrue(
                forwarded < 2L * (1 << 20),
                "forwarded " + forwarded + " characters despite the bound");
    }

    /**
     * {@code docker run --rm} reclaims the container asynchronously, so give it a moment before
     * failing.
     */
    private static boolean awaitContainerGone(String containerName) {
        for (int i = 0; i < 40; i++) {
            if (listContainers(containerName).isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static String listContainers(String containerName) {
        try {
            Process process =
                    new ProcessBuilder(
                                    "docker",
                                    "ps",
                                    "--all",
                                    "--filter",
                                    "name=^" + containerName + "$",
                                    "--format",
                                    "{{.Names}}")
                            .redirectErrorStream(true)
                            .start();
            String output =
                    new String(
                                    process.getInputStream().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .trim();
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            return output;
        } catch (Exception e) {
            throw new AssertionError("could not list docker containers", e);
        }
    }

    private static String containerNameOf(ClaimedTaskResponse task) {
        return "forge-task-" + task.taskRunId() + "-" + task.attemptId();
    }

    private static ClaimedTaskResponse task(List<String> command, int timeoutSeconds) {
        return task(command, timeoutSeconds, 1);
    }

    /**
     * {@code taskRunId} names the container, so cleanup tests need one nobody else in this class
     * uses.
     */
    private static ClaimedTaskResponse task(
            List<String> command, int timeoutSeconds, long taskRunId) {
        return new ClaimedTaskResponse(
                taskRunId,
                1L,
                1L,
                "test:task",
                "sha256:test",
                command,
                new ArrayList<>(),
                List.of(),
                timeoutSeconds,
                1,
                1L,
                "lease-token");
    }
}
