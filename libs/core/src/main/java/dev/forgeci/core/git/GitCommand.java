package dev.forgeci.core.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a {@code git} subprocess in a directory and returns its stdout. */
final class GitCommand {

    private static final long TIMEOUT_SECONDS = 60;

    private GitCommand() {}

    static String run(Path directory, String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("git");
        command.addAll(List.of(arguments));

        Process process;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile()).start();
        } catch (IOException e) {
            throw new GitException(
                    "could not run git ("
                            + e.getMessage()
                            + "). Install Git and make sure it is on your PATH.");
        }

        // stderr is drained concurrently so a chatty git can never fill its pipe and deadlock us
        StringBuilder stderr = new StringBuilder();
        Thread drain = new Thread(() -> stderr.append(readFully(process.getErrorStream())));
        drain.setDaemon(true);
        drain.start();

        String stdout;
        try {
            stdout = readFully(process.getInputStream());
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new GitException(describe(arguments) + " timed out after " + TIMEOUT_SECONDS + "s");
            }
            drain.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitException("interrupted while running " + describe(arguments));
        }

        if (process.exitValue() != 0) {
            String detail = stderr.toString().trim();
            throw new GitException(
                    describe(arguments)
                            + " failed with exit code "
                            + process.exitValue()
                            + (detail.isEmpty() ? "" : ": " + detail));
        }
        return stdout;
    }

    /** Runs a command whose non-zero exit is a meaningful answer rather than an error. */
    static boolean succeeds(Path directory, String... arguments) {
        try {
            run(directory, arguments);
            return true;
        } catch (GitException e) {
            return false;
        }
    }

    private static String readFully(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GitException("failed to read git output: " + e.getMessage());
        }
    }

    private static String describe(String[] arguments) {
        return "git " + String.join(" ", arguments);
    }
}
