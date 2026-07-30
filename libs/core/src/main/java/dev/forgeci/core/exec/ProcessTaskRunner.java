package dev.forgeci.core.exec;

import dev.forgeci.core.model.TaskDefinition;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a task's declared command as a direct child process — no shell, so nothing in
 * {@code forgeci.yml} can be interpreted as shell syntax. Enforces the task timeout, bounds captured
 * output, and terminates the whole process tree on timeout or cancellation.
 */
public final class ProcessTaskRunner implements TaskRunner {

    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);
    private static final long MAX_OUTPUT_BYTES = 1 << 20;
    /** Passed through to every task so commands remain resolvable and tools find a home directory. */
    private static final List<String> ALWAYS_INHERITED = List.of("PATH", "HOME", "TMPDIR", "LANG");

    private final Path workingDirectory;

    public ProcessTaskRunner(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    @Override
    public TaskOutcome run(TaskDefinition task, Duration timeout, ExecutionListener listener) {
        listener.taskStarted(task.name(), task.command());
        long startedAt = System.nanoTime();

        ProcessBuilder builder =
                new ProcessBuilder(task.command())
                        .directory(workingDirectory.toFile())
                        .redirectErrorStream(true);
        applyEnvironment(builder, task.environment());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return TaskOutcome.failedToStart(
                    task.name(),
                    elapsedSince(startedAt),
                    "could not start '" + task.command().get(0) + "': " + e.getMessage());
        }

        Thread pump = startOutputPump(task.name(), process, listener);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminateTree(process);
                joinQuietly(pump);
                return TaskOutcome.timedOut(task.name(), timeout);
            }
            joinQuietly(pump);
            int exitCode = process.exitValue();
            return exitCode == 0
                    ? TaskOutcome.succeeded(task.name(), elapsedSince(startedAt))
                    : TaskOutcome.failed(task.name(), exitCode, elapsedSince(startedAt));
        } catch (InterruptedException e) {
            terminateTree(process);
            joinQuietly(pump);
            Thread.currentThread().interrupt();
            return TaskOutcome.canceled(task.name(), elapsedSince(startedAt));
        }
    }

    /**
     * Starts from an empty environment so a task's result depends only on what it declares — the
     * same reason cache keys will only ever see the allowlist (phase 2).
     */
    private static void applyEnvironment(ProcessBuilder builder, List<String> allowlist) {
        Map<String, String> parent = Map.copyOf(builder.environment());
        builder.environment().clear();
        for (String name : ALWAYS_INHERITED) {
            String value = parent.get(name);
            if (value != null) {
                builder.environment().put(name, value);
            }
        }
        for (String name : allowlist) {
            String value = parent.get(name);
            if (value != null) {
                builder.environment().put(name, value);
            }
        }
    }

    private static Thread startOutputPump(String task, Process process, ExecutionListener listener) {
        Thread pump =
                new Thread(
                        () -> {
                            long forwarded = 0;
                            boolean truncated = false;
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (forwarded >= MAX_OUTPUT_BYTES) {
                                        if (!truncated) {
                                            truncated = true;
                                            listener.taskOutput(
                                                    task, "[output truncated at " + MAX_OUTPUT_BYTES + " bytes]");
                                        }
                                        continue;
                                    }
                                    forwarded += line.length() + 1;
                                    listener.taskOutput(task, line);
                                }
                            } catch (IOException e) {
                                // the stream closing under us means the process is gone; its exit code decides
                            }
                        },
                        "forge-output-" + task);
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    /** SIGTERM the whole tree, then SIGKILL whatever is still alive after a short grace period. */
    private static void terminateTree(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.onExit().get(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // still running after the grace period — fall through and force it
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private static void joinQuietly(Thread pump) {
        try {
            pump.join(TERMINATION_GRACE.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration elapsedSince(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }
}
