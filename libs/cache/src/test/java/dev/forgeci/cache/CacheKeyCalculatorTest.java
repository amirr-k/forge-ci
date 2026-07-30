package dev.forgeci.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.forgeci.core.model.TaskDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheKeyCalculatorTest {

    private static final String TOOLCHAIN = "Java 21.0.5";

    @Test
    void sameInputsProduceTheSameKey(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("src.txt"), "hello\n");
        TaskDefinition task = task("a:build", List.of("src.txt"));

        CacheKey first = compute(directory, task, Map.of(), Map.of());
        CacheKey second = compute(directory, task, Map.of(), Map.of());

        assertEquals(first.value(), second.value());
    }

    @Test
    void inputOrderingDoesNotAffectTheKey(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("a.txt"), "a\n");
        Files.writeString(directory.resolve("b.txt"), "b\n");
        TaskDefinition task = task("a:build", List.of("b.txt", "a.txt"));

        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("shared:build", "digest-1");
        forward.put("other:build", "digest-2");
        Map<String, String> backward = new LinkedHashMap<>();
        backward.put("other:build", "digest-2");
        backward.put("shared:build", "digest-1");

        CacheKey withForwardOrder = compute(directory, task, Map.of(), forward);
        CacheKey withBackwardOrder = compute(directory, task, Map.of(), backward);

        assertEquals(withForwardOrder.value(), withBackwardOrder.value());
    }

    @Test
    void aMeaningfulFileChangeInvalidatesTheKey(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("src.txt");
        Files.writeString(file, "hello\n");
        TaskDefinition task = task("a:build", List.of("src.txt"));
        CacheKey before = compute(directory, task, Map.of(), Map.of());

        Files.writeString(file, "goodbye\n");
        CacheKey after = compute(directory, task, Map.of(), Map.of());

        assertNotEquals(before.value(), after.value());
    }

    @Test
    void anUnrelatedFileChangeDoesNotInvalidateTheKey(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("src.txt"), "hello\n");
        Files.writeString(directory.resolve("unrelated.txt"), "v1\n");
        TaskDefinition task = task("a:build", List.of("src.txt"));
        CacheKey before = compute(directory, task, Map.of(), Map.of());

        Files.writeString(directory.resolve("unrelated.txt"), "v2\n");
        CacheKey after = compute(directory, task, Map.of(), Map.of());

        assertEquals(before.value(), after.value());
    }

    @Test
    void aToolchainChangeInvalidatesAnIncompatibleKey(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("src.txt"), "hello\n");
        TaskDefinition task = task("a:build", List.of("src.txt"));

        CacheKey withJava21 =
                CacheKeyCalculator.compute(directory, task, Map.of(), Map.of(), "Java 21.0.5");
        CacheKey withJava22 =
                CacheKeyCalculator.compute(directory, task, Map.of(), Map.of(), "Java 22.0.1");

        assertNotEquals(withJava21.value(), withJava22.value());
    }

    @Test
    void aSelectedEnvironmentValueChangeInvalidatesTheKey(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("src.txt"), "hello\n");
        TaskDefinition task = task("a:build", List.of("src.txt"));

        CacheKey before = compute(directory, task, Map.of("STAGE", "dev"), Map.of());
        CacheKey after = compute(directory, task, Map.of("STAGE", "prod"), Map.of());

        assertNotEquals(before.value(), after.value());
    }

    private static CacheKey compute(
            Path directory, TaskDefinition task, Map<String, String> environment, Map<String, String> dependencies) {
        return CacheKeyCalculator.compute(directory, task, environment, dependencies, TOOLCHAIN);
    }

    private static TaskDefinition task(String name, List<String> inputs) {
        return new TaskDefinition(name, List.of(), inputs, List.of(), List.of("true"), List.of(), null, true);
    }
}
