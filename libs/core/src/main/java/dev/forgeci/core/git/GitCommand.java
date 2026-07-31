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

        // both streams are drained concurrently: a chatty git must never fill a pipe and deadlock
        // us,
        // and waiting on the process itself is what makes the timeout below able to fire
        StringBuffer stdout = new StringBuffer();
        StringBuffer stderr = new StringBuffer();
        Thread drainOut = drain(process.getInputStream(), stdout);
        Thread drainErr = drain(process.getErrorStream(), stderr);

        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new GitException(
                        describe(arguments) + " timed out after " + TIMEOUT_SECONDS + "s");
            }
            drainOut.join();
            drainErr.join();
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
        return stdout.toString();
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

    private static Thread drain(InputStream stream, StringBuffer into) {
        Thread thread =
                new Thread(
                        () -> {
                            try (stream) {
                                into.append(
                                        new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                            } catch (IOException e) {
                                // the process died mid-read; its exit code is what decides the
                                // outcome
                            }
                        },
                        "forge-git-drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String describe(String[] arguments) {
        return "git " + String.join(" ", arguments);
    }
}
