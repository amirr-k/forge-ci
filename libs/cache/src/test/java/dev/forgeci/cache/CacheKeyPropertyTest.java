package dev.forgeci.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.forgeci.core.model.TaskDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The two cache-key guarantees, checked over generated tasks rather than one hand-written pair:
 * canonically identical inputs always agree, and no single declared contributor can change without
 * the key changing with it. Each case is driven by its seed, which is the test's display name, so a
 * failure names the exact input to replay.
 */
class CacheKeyPropertyTest {

    private static final String TOOLCHAIN = "Java 21.0.5";

    private static LongStream seeds() {
        return LongStream.rangeClosed(1, 150);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void theSameCanonicalInputsAlwaysProduceTheSameKey(long seed, @TempDir Path directory) throws IOException {
        Scenario scenario = Scenario.generate(seed, directory);

        CacheKey first = scenario.compute();
        // the same declaration spelled with its lists and maps in a different order
        CacheKey second = scenario.permuted(seed).compute();

        assertEquals(first.value(), second.value(), "declaration order is not part of a task's identity");
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void changingAnyOneDeclaredContributorChangesTheKey(long seed, @TempDir Path directory) throws IOException {
        Scenario scenario = Scenario.generate(seed, directory);
        String original = scenario.compute().value();

        for (Map.Entry<String, CacheKey> mutation : scenario.mutations(seed).entrySet()) {
            assertNotEquals(original, mutation.getValue().value(), "changing " + mutation.getKey() + " left the key untouched");
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void aFileNoInputGlobDeclaresNeverChangesTheKey(long seed, @TempDir Path directory) throws IOException {
        Scenario scenario = Scenario.generate(seed, directory);
        String original = scenario.compute().value();

        Files.writeString(directory.resolve("undeclared-" + seed + ".txt"), "not an input of any task\n");

        assertEquals(original, scenario.compute().value());
    }

    /** One generated task plus everything the calculator is allowed to read: its files, environment, and dependency digests. */
    private record Scenario(
            Path directory, TaskDefinition task, Map<String, String> environment, Map<String, String> dependencyDigests, String toolchain) {

        static Scenario generate(long seed, Path directory) throws IOException {
            Random random = new Random(seed);
            List<String> inputs = new ArrayList<>();
            for (int i = 0; i <= random.nextInt(4); i++) {
                String name = "src/file" + i + ".txt";
                Files.createDirectories(directory.resolve("src"));
                Files.writeString(directory.resolve(name), "content-" + random.nextInt(1000) + "\n");
                inputs.add(name);
            }

            Map<String, String> environment = new LinkedHashMap<>();
            for (int i = 0; i < random.nextInt(3); i++) {
                environment.put("FORGE_VAR_" + i, "value-" + random.nextInt(1000));
            }
            Map<String, String> dependencyDigests = new LinkedHashMap<>();
            for (int i = 0; i < random.nextInt(3); i++) {
                dependencyDigests.put("dep" + i + ":build", "sha256:" + random.nextInt(100000));
            }

            TaskDefinition task =
                    new TaskDefinition(
                            "module" + random.nextInt(10) + ":build",
                            new ArrayList<>(dependencyDigests.keySet()),
                            inputs,
                            List.of("build/out/**"),
                            List.of("./gradlew", "build"),
                            new ArrayList<>(environment.keySet()),
                            "10m",
                            true);
            return new Scenario(directory, task, environment, dependencyDigests, TOOLCHAIN);
        }

        CacheKey compute() {
            return CacheKeyCalculator.compute(directory, task, environment, dependencyDigests, toolchain);
        }

        Scenario permuted(long seed) {
            Random random = new Random(~seed);
            List<String> dependsOn = shuffled(task.dependsOn(), random);
            List<String> inputs = shuffled(task.inputs(), random);
            TaskDefinition reordered =
                    new TaskDefinition(
                            task.name(), dependsOn, inputs, task.outputs(), task.command(), task.environment(), task.timeout(), task.cacheable());
            return new Scenario(directory, reordered, reversed(environment), reversed(dependencyDigests), toolchain);
        }

        /** One key per single-contributor mutation, each differing from the original in exactly one way. */
        Map<String, CacheKey> mutations(long seed) throws IOException {
            Map<String, CacheKey> keys = new LinkedHashMap<>();
            keys.put(
                    "the task name",
                    CacheKeyCalculator.compute(directory, withName(task.name() + "-renamed"), environment, dependencyDigests, toolchain));
            keys.put("the command", CacheKeyCalculator.compute(directory, withCommand(List.of("./gradlew", "assemble")), environment, dependencyDigests, toolchain));
            keys.put("the declared outputs", CacheKeyCalculator.compute(directory, withOutputs(List.of("build/elsewhere/**")), environment, dependencyDigests, toolchain));
            keys.put("the timeout", CacheKeyCalculator.compute(directory, withTimeout("30m"), environment, dependencyDigests, toolchain));
            keys.put("the toolchain", CacheKeyCalculator.compute(directory, task, environment, dependencyDigests, toolchain + "-patched"));
            keys.put("an added environment value", CacheKeyCalculator.compute(directory, task, plus(environment, "FORGE_EXTRA", "x"), dependencyDigests, toolchain));
            keys.put(
                    "a dependency's artifact digest",
                    CacheKeyCalculator.compute(directory, task, environment, plus(dependencyDigests, "extra:build", "sha256:new"), toolchain));

            // mutate a declared source file last: it changes the on-disk state every later compute would see
            if (!task.inputs().isEmpty()) {
                String changed = task.inputs().get(0);
                String before = Files.readString(directory.resolve(changed));
                Files.writeString(directory.resolve(changed), before + "one more line\n");
                keys.put("a declared source file's contents", compute());
                Files.writeString(directory.resolve(changed), before);
            }
            return keys;
        }

        private TaskDefinition withName(String name) {
            return new TaskDefinition(
                    name, task.dependsOn(), task.inputs(), task.outputs(), task.command(), task.environment(), task.timeout(), task.cacheable());
        }

        private TaskDefinition withCommand(List<String> command) {
            return new TaskDefinition(
                    task.name(), task.dependsOn(), task.inputs(), task.outputs(), command, task.environment(), task.timeout(), task.cacheable());
        }

        private TaskDefinition withOutputs(List<String> outputs) {
            return new TaskDefinition(
                    task.name(), task.dependsOn(), task.inputs(), outputs, task.command(), task.environment(), task.timeout(), task.cacheable());
        }

        private TaskDefinition withTimeout(String timeout) {
            return new TaskDefinition(
                    task.name(), task.dependsOn(), task.inputs(), task.outputs(), task.command(), task.environment(), timeout, task.cacheable());
        }

        private static List<String> shuffled(List<String> values, Random random) {
            List<String> copy = new ArrayList<>(values);
            Collections.shuffle(copy, random);
            return copy;
        }

        private static Map<String, String> reversed(Map<String, String> map) {
            List<String> keys = new ArrayList<>(map.keySet());
            Collections.reverse(keys);
            Map<String, String> result = new LinkedHashMap<>();
            for (String key : keys) {
                result.put(key, map.get(key));
            }
            return result;
        }

        private static Map<String, String> plus(Map<String, String> map, String key, String value) {
            Map<String, String> result = new LinkedHashMap<>(map);
            result.put(key, value);
            return result;
        }
    }
}
