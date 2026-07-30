package dev.forgeci.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A throwaway Git repository for tests. Drives the real {@code git} binary rather than a stub,
 * because the behavior under test is exactly ForgeCI's reading of real Git output.
 */
public final class GitTestRepository {

    private final Path directory;

    private GitTestRepository(Path directory) {
        this.directory = directory;
    }

    public static GitTestRepository initialize(Path directory) {
        GitTestRepository repository = new GitTestRepository(directory);
        repository.git("init", "--quiet", "--initial-branch=main");
        repository.git("config", "user.email", "test@forgeci.dev");
        repository.git("config", "user.name", "ForgeCI Test");
        return repository;
    }

    public Path directory() {
        return directory;
    }

    public GitTestRepository write(String relativePath, String content) {
        Path file = directory.resolve(relativePath);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public GitTestRepository delete(String relativePath) {
        try {
            Files.delete(directory.resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public GitTestRepository move(String from, String to) {
        git("mv", from, to);
        return this;
    }

    public GitTestRepository commitAll(String message) {
        git("add", "--all");
        git("commit", "--quiet", "--message", message);
        return this;
    }

    public String git(String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("git");
        command.addAll(List.of(arguments));
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
            // keep commits reproducible and independent of the developer's global git config
            Map<String, String> environment = builder.environment();
            environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
            environment.put("GIT_CONFIG_SYSTEM", "/dev/null");
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("git " + String.join(" ", arguments) + " hung");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "git " + String.join(" ", arguments) + " failed: " + output.trim());
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
