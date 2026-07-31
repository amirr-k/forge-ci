package dev.forgeci.controlplane.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Owns the single mutable, on-disk copy of the bundled demo repo that the control plane hashes task
 * inputs against. Workers execute the same {@code scripts/apply-scenario} against their own shared
 * workspace volume (apps/worker/Dockerfile, deploy/compose.yaml) — this class never talks to that
 * volume directly, it only needs its own copy to produce real, content-derived cache keys. Both
 * copies apply the identical script for the identical scenario id, so they always agree on content
 * even though they're on different hosts.
 */
@Component
public class DemoWorkspace {

    private final Path sourceRepo;
    private final Path workspace;

    public DemoWorkspace(
            @Value("${forge.demo.repo-path}") String sourceRepo,
            @Value("${forge.demo.workspace-path}") String workspace) {
        this.sourceRepo = Path.of(sourceRepo);
        this.workspace = Path.of(workspace);
    }

    /** Copies the baked-in bundle into the mutable workspace path once, if it isn't there yet. */
    public synchronized void ensureSeeded() {
        if (Files.exists(workspace.resolve("forgeci.yml"))) {
            return;
        }
        try {
            Files.createDirectories(workspace);
            copyTree(sourceRepo, workspace);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to seed demo workspace at " + workspace, e);
        }
    }

    /** Resets to baseline, then overlays exactly the files {@code scenario} changes. */
    public synchronized Path applyScenario(DemoScenario scenario) {
        ensureSeeded();
        try {
            Process process =
                    new ProcessBuilder("./scripts/apply-scenario", scenario.scriptId())
                            .directory(workspace.toFile())
                            .redirectErrorStream(true)
                            .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(
                        "apply-scenario " + scenario.scriptId() + " failed: " + output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted applying demo scenario " + scenario.scriptId(), e);
        }
        return workspace;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path destination = target.resolve(source.relativize(file));
                if (Files.isDirectory(file)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Test/dev support: drop the mutable copy so the next {@link #ensureSeeded()} reseeds clean.
     */
    void reset() {
        if (!Files.exists(workspace)) {
            return;
        }
        try (Stream<Path> files = Files.walk(workspace)) {
            files.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
