package dev.forgeci.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.cache.CacheKey;
import dev.forgeci.cache.CacheKeyCalculator;
import dev.forgeci.cache.HttpRemoteArtifactClient;
import dev.forgeci.cache.RemoteArtifactClient;
import dev.forgeci.cache.TaskCache;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.core.model.TaskDefinition;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The "remote incremental build" end-to-end scenario from the required test taxonomy: two
 * completely independent workspaces (no shared JVM state, no fake stub) sharing one real control
 * plane over the actual wire protocol {@code libs/cache}'s {@link HttpRemoteArtifactClient} and
 * {@code apps/cli} both use. Workspace A does a cold build and uploads for real; workspace B — a
 * fresh directory, nothing local cached — reuses it over HTTP alone, exactly what a second
 * developer's clean checkout or a second CI runner would see.
 */
class RemoteIncrementalBuildEndToEndTest extends ControlPlaneIntegrationTest {

    private static final String TOOLCHAIN = "Java 21.0.5";

    @LocalServerPort private int port;

    @Test
    void aSecondWorkspaceWithNothingLocalReusesTheFirstWorkspacesOutputOverRealHttp()
            throws IOException {
        RemoteArtifactClient remote =
                new HttpRemoteArtifactClient(URI.create("http://localhost:" + port + "/"));
        long projectId =
                remote.ensureProject(
                        "e2e-remote-incremental-" + UUID.randomUUID(),
                        "git@example.com:e2e/e2e.git",
                        "main",
                        1);

        Path producer = Files.createTempDirectory("forge-e2e-producer");
        Path consumer = Files.createTempDirectory("forge-e2e-consumer");
        try {
            Files.createDirectories(producer.resolve("src"));
            Files.writeString(producer.resolve("src/Main.java"), "class Main {}\n");
            TaskDefinition task =
                    new TaskDefinition(
                            "e2e:build",
                            List.of(),
                            List.of("src/**"),
                            List.of("build/out.txt"),
                            List.of("true"),
                            List.of(),
                            "10m",
                            true);
            CacheKey key =
                    CacheKeyCalculator.compute(producer, task, Map.of(), Map.of(), TOOLCHAIN);

            // workspace A: a genuine cold build — nothing cached anywhere yet
            TaskCache producerCache = new TaskCache(producer, remote, projectId);
            assertThat(producerCache.lookup(key)).isEmpty();
            Files.createDirectories(producer.resolve("build"));
            Files.writeString(producer.resolve("build/out.txt"), "built by workspace A\n");
            TaskCache.CacheHit produced = producerCache.store(key, task.outputs());

            // workspace B: a fresh directory that has never talked to this control plane before,
            // computing the exact same key from its own copy of the identical source
            Files.createDirectories(consumer.resolve("src"));
            Files.writeString(consumer.resolve("src/Main.java"), "class Main {}\n");
            CacheKey sameKey =
                    CacheKeyCalculator.compute(consumer, task, Map.of(), Map.of(), TOOLCHAIN);
            assertThat(sameKey.value()).isEqualTo(key.value());

            TaskCache consumerCache = new TaskCache(consumer, remote, projectId);
            Optional<TaskCache.CacheHit> hit = consumerCache.lookup(sameKey);
            assertThat(hit)
                    .as(
                            "a fresh workspace must reuse the first workspace's output over the remote store alone")
                    .isPresent();
            assertThat(hit.get().digest()).isEqualTo(produced.digest());

            consumerCache.restore(hit.get());
            assertThat(Files.readString(consumer.resolve("build/out.txt")))
                    .isEqualTo("built by workspace A\n");
        } finally {
            deleteRecursively(producer);
            deleteRecursively(consumer);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> path.toFile().delete());
        }
    }
}
