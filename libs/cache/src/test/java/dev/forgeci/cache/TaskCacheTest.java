package dev.forgeci.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.core.model.TaskDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskCacheTest {

    private static final String TOOLCHAIN = "Java 21.0.5";

    @Test
    void storedOutputsAreRestoredOnAVerifiedHit(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory.resolve("build"));
        Files.writeString(directory.resolve("build/out.txt"), "result\n");
        TaskDefinition task = task("a:build", List.of("build/out.txt"));
        TaskCache cache = new TaskCache(directory);
        CacheKey key = CacheKeyCalculator.compute(directory, task, Map.of(), Map.of(), TOOLCHAIN);

        cache.store(key, task.outputs());
        Files.delete(directory.resolve("build/out.txt"));

        Optional<TaskCache.CacheHit> hit = cache.lookup(key);
        assertTrue(hit.isPresent());
        cache.restore(hit.get());
        assertEquals("result\n", Files.readString(directory.resolve("build/out.txt")));
    }

    @Test
    void aCorruptArtifactIsRejectedAsAMiss(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory.resolve("build"));
        Files.writeString(directory.resolve("build/out.txt"), "result\n");
        TaskDefinition task = task("a:build", List.of("build/out.txt"));
        TaskCache cache = new TaskCache(directory);
        CacheKey key = CacheKeyCalculator.compute(directory, task, Map.of(), Map.of(), TOOLCHAIN);

        cache.store(key, task.outputs());
        corruptTheOnlyStoredObject(directory);

        assertFalse(cache.lookup(key).isPresent(), "a corrupted object must never be reported as a hit");
    }

    @Test
    void aManifestWithoutAStoredObjectIsNeverReportedAsAHit(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory.resolve("build"));
        Files.writeString(directory.resolve("build/out.txt"), "result\n");
        TaskDefinition task = task("a:build", List.of("build/out.txt"));
        TaskCache cache = new TaskCache(directory);
        CacheKey key = CacheKeyCalculator.compute(directory, task, Map.of(), Map.of(), TOOLCHAIN);

        cache.store(key, task.outputs());
        deleteAllStoredObjects(directory);

        assertFalse(cache.lookup(key).isPresent());
    }

    @Test
    void explainReportsAMissWithNoPriorRecordAsUncached(@TempDir Path directory) {
        TaskDefinition task = task("a:build", List.of());
        CacheKey key = CacheKeyCalculator.compute(directory, task, Map.of(), Map.of(), TOOLCHAIN);

        String reason = TaskCache.explainReason(Optional.empty(), key, false);

        assertEquals("no cache entry for this task yet", reason);
    }

    private static void corruptTheOnlyStoredObject(Path directory) throws IOException {
        Path objects = directory.resolve(".forge/cache/objects");
        try (Stream<Path> files = Files.walk(objects)) {
            Path object = files.filter(Files::isRegularFile).findFirst().orElseThrow();
            Files.write(object, "corrupted".getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static void deleteAllStoredObjects(Path directory) throws IOException {
        Path objects = directory.resolve(".forge/cache/objects");
        try (Stream<Path> files = Files.walk(objects)) {
            for (Path file : files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                if (!file.equals(objects)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static TaskDefinition task(String name, List<String> outputs) {
        return new TaskDefinition(name, List.of(), List.of(), outputs, List.of("true"), List.of(), null, true);
    }
}
