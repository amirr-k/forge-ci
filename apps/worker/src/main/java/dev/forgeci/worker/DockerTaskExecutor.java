package dev.forgeci.worker;

import dev.forgeci.protocol.ClaimedTaskResponse;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Runs one claimed task as a bounded {@code docker run}, never through a shell — the task's own
 * declared {@code command} array is appended verbatim as the container's entrypoint arguments, so
 * nothing ForgeCI adds can be read as shell syntax. CPU, memory, wall-clock time, and captured
 * output are all bounded, mirroring {@code libs/core}'s local {@code ProcessTaskRunner} one level
 * out (a container instead of a bare child process).
 */
public final class DockerTaskExecutor {

    private static final java.time.Duration TERMINATION_GRACE = java.time.Duration.ofSeconds(3);
    private static final long MAX_OUTPUT_CHARS = 1 << 20;
    private static final int MAX_LINE_CHARS = 1 << 16;
    private static final int LOG_BATCH_SIZE = 20;

    public record ExecutionResult(boolean success, Integer exitCode, String failureReason) {}

    private final WorkerConfig config;

    public DockerTaskExecutor(WorkerConfig config) {
        this.config = config;
    }

    /** {@code logSink} receives output in small batches as the container produces it, for streaming to the control plane. */
    public ExecutionResult run(ClaimedTaskResponse task, Consumer<List<String>> logSink) {
        String containerName = "forge-task-" + task.taskRunId() + "-" + task.attemptId();
        List<String> args = new ArrayList<>();
        args.add("docker");
        args.add("run");
        args.add("--rm");
        args.add("--name");
        args.add(containerName);
        args.add("--cpus");
        args.add(config.dockerCpus());
        args.add("--memory");
        args.add(config.dockerMemory());
        args.add("-w");
        args.add("/workspace");
        args.add("-v");
        args.add(config.dockerMountSource() + ":/workspace");
        for (String envName : task.environment()) {
            String value = System.getenv(envName);
            if (value != null) {
                args.add("-e");
                args.add(envName + "=" + value);
            }
        }
        args.add(config.dockerImage());
        args.addAll(task.command());

        Process process;
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
        } catch (IOException e) {
            return new ExecutionResult(false, null, "could not start docker: " + e.getMessage());
        }
        closeQuietly(process.getOutputStream());

        Thread pump = startOutputPump(process, logSink);
        try {
            boolean finished = process.waitFor(task.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                killContainer(containerName);
                terminate(process);
                joinQuietly(pump);
                return new ExecutionResult(false, null, "task timed out after " + task.timeoutSeconds() + "s");
            }
            joinQuietly(pump);
            int exitCode = process.exitValue();
            return exitCode == 0 ? new ExecutionResult(true, 0, null) : new ExecutionResult(false, exitCode, "task exited with code " + exitCode);
        } catch (InterruptedException e) {
            killContainer(containerName);
            terminate(process);
            joinQuietly(pump);
            Thread.currentThread().interrupt();
            return new ExecutionResult(false, null, "worker shutting down");
        }
    }

    private static void killContainer(String containerName) {
        try {
            new ProcessBuilder("docker", "kill", containerName).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor(TERMINATION_GRACE.toSeconds(), TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            // best-effort: --rm plus the process-tree termination below still reclaims it
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
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
    }

    private static Thread startOutputPump(Process process, Consumer<List<String>> logSink) {
        Thread pump =
                new Thread(
                        () -> {
                            List<String> batch = new ArrayList<>(LOG_BATCH_SIZE);
                            StringBuilder line = new StringBuilder();
                            long forwarded = 0;
                            boolean truncated = false;
                            char[] buffer = new char[8192];
                            try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                                int read;
                                while ((read = reader.read(buffer)) != -1) {
                                    for (int i = 0; i < read; i++) {
                                        char c = buffer[i];
                                        if (c == '\n' || line.length() >= MAX_LINE_CHARS) {
                                            forwarded += line.length() + 1;
                                            if (forwarded <= MAX_OUTPUT_CHARS) {
                                                batch.add(stripCarriageReturn(line));
                                            } else if (!truncated) {
                                                truncated = true;
                                                batch.add("[output truncated at " + MAX_OUTPUT_CHARS + " characters]");
                                            }
                                            line.setLength(0);
                                            if (c != '\n') {
                                                line.append(c);
                                            }
                                            if (batch.size() >= LOG_BATCH_SIZE) {
                                                logSink.accept(List.copyOf(batch));
                                                batch.clear();
                                            }
                                        } else {
                                            line.append(c);
                                        }
                                    }
                                }
                                if (line.length() > 0 && !truncated) {
                                    batch.add(stripCarriageReturn(line));
                                }
                                if (!batch.isEmpty()) {
                                    logSink.accept(List.copyOf(batch));
                                }
                            } catch (IOException e) {
                                // the stream closing under us means the container is gone; its exit code decides
                            }
                        },
                        "forge-worker-output");
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    private static String stripCarriageReturn(StringBuilder line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') {
            end--;
        }
        return line.substring(0, end);
    }

    private static void closeQuietly(Closeable stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // nothing to do if stdin can't be closed
        }
    }

    private static void joinQuietly(Thread pump) {
        try {
            pump.join(TERMINATION_GRACE.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
