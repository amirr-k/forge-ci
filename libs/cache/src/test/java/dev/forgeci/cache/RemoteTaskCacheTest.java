package dev.forgeci.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.core.model.TaskDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TaskCache's local/remote precedence: local first, remote only on a local miss, local mode
 * unaffected when remote is absent or fails.
 */
class RemoteTaskCacheTest {

    private static final String TOOLCHAIN = "Java 21.0.5";
    private static final long PROJECT_ID = 1L;

    @Test
    void aRemoteHitIsRestoredAndAlsoWrittenToTheLocalCache(
            @TempDir Path producer, @TempDir Path consumer) throws IOException {
        Files.createDirectories(producer.resolve("build"));
        Files.writeString(producer.resolve("build/out.txt"), "result\n");
        TaskDefinition task = task("a:build", List.of("build/out.txt"));
        CacheKey key = CacheKeyCalculator.compute(producer, task, Map.of(), Map.of(), TOOLCHAIN);

        FakeRemote remote = new FakeRemote();
        TaskCache producerCache = new TaskCache(producer, remote, PROJECT_ID);
        producerCache.store(key, task.outputs());
        assertTrue(
                remote.uploaded.containsKey(key.value()),
                "store() must upload to a configured remote");

        // a second, independent workspace with nothing in its own local cache
        Files.createDirectories(consumer.resolve("build"));
        TaskCache consumerCache = new TaskCache(consumer, remote, PROJECT_ID);
        Optional<TaskCache.CacheHit> hit = consumerCache.lookup(key);
        assertTrue(hit.isPresent(), "a local miss must fall back to a configured remote");
        consumerCache.restore(hit.get());
        assertEquals("result\n", Files.readString(consumer.resolve("build/out.txt")));

        // now local to the consumer too, without asking the remote again
        remote.lookups.clear();
        assertTrue(consumerCache.lookup(key).isPresent());
        assertTrue(remote.lookups.isEmpty(), "a remote hit must be adopted into the local cache");
    }

    @Test
    void noRemoteConfiguredBehavesExactlyAsLocalOnlyMode(@TempDir Path directory) {
        TaskDefinition task = task("a:build", List.of());
        CacheKey key = CacheKeyCalculator.compute(directory, task, Map.of(), Map.of(), TOOLCHAIN);
        TaskCache cache = new TaskCache(directory);

        assertFalse(cache.lookup(key).isPresent());
    }

    @Test
    void anUnreachableRemoteDegradesToALocalMissRatherThanFailing(@TempDir Path directory) {
        TaskDefinition task = task("a:build", List.of());
        CacheKey key = CacheKeyCalculator.compute(directory, task, Map.of(), Map.of(), TOOLCHAIN);
        TaskCache cache = new TaskCache(directory, new UnreachableRemote(), PROJECT_ID);

        assertFalse(cache.lookup(key).isPresent());
    }

    @Test
    void storeStillSucceedsLocallyWhenTheRemoteUploadFails(@TempDir Path directory)
            throws IOException {
        Files.createDirectories(directory.resolve("build"));
        Files.writeString(directory.resolve("build/out.txt"), "result\n");
        TaskDefinition task = task("a:build", List.of("build/out.txt"));
        CacheKey key = CacheKeyCalculator.compute(directory, task, Map.of(), Map.of(), TOOLCHAIN);
        TaskCache cache = new TaskCache(directory, new UnreachableRemote(), PROJECT_ID);

        cache.store(key, task.outputs());

        assertTrue(
                cache.lookup(key).isPresent(),
                "a local store must succeed even when the remote upload fails");
    }

    private static TaskDefinition task(String name, List<String> outputs) {
        return new TaskDefinition(
                name, List.of(), List.of(), outputs, List.of("true"), List.of(), null, true);
    }

    private static final class FakeRemote implements RemoteArtifactClient {
        final Map<String, byte[]> uploaded = new HashMap<>();
        final Map<String, Boolean> lookups = new HashMap<>();

        @Override
        public long ensureProject(
                String name, String repositoryIdentity, String defaultBranch, int configVersion) {
            return PROJECT_ID;
        }

        @Override
        public Optional<byte[]> lookup(long projectId, String cacheKey) {
            lookups.put(cacheKey, true);
            return Optional.ofNullable(uploaded.get(cacheKey));
        }

        @Override
        public void upload(long projectId, String cacheKey, byte[] archive) {
            uploaded.put(cacheKey, archive);
        }
    }

    private static final class UnreachableRemote implements RemoteArtifactClient {
        @Override
        public long ensureProject(
                String name, String repositoryIdentity, String defaultBranch, int configVersion) {
            throw new RemoteCacheUnavailableException("control plane unreachable");
        }

        @Override
        public Optional<byte[]> lookup(long projectId, String cacheKey) {
            throw new RemoteCacheUnavailableException("control plane unreachable");
        }

        @Override
        public void upload(long projectId, String cacheKey, byte[] archive) {
            throw new RemoteCacheUnavailableException("control plane unreachable");
        }
    }
}
