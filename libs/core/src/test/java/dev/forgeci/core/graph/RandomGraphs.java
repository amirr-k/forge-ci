package dev.forgeci.core.graph;

import dev.forgeci.core.model.Defaults;
import dev.forgeci.core.model.ForgeConfig;
import dev.forgeci.core.model.ProjectInfo;
import dev.forgeci.core.model.TaskDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Seeded generator of random acyclic {@link ForgeConfig}s for the property tests. Every graph is a
 * DAG by construction — a task may only depend on tasks generated before it — so a property test
 * failure is always about the property, never about an accidentally cyclic input. The seed is part
 * of each generated config's project name so a failing case can be replayed exactly.
 */
final class RandomGraphs {

    private RandomGraphs() {}

    static ForgeConfig dag(long seed) {
        Random random = new Random(seed);
        int taskCount = 1 + random.nextInt(12);
        List<String> names = new ArrayList<>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            names.add("module" + i + (random.nextBoolean() ? ":build" : ":test"));
        }

        Map<String, TaskDefinition> tasks = new LinkedHashMap<>();
        for (int i = 0; i < taskCount; i++) {
            List<String> dependsOn = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                if (random.nextInt(4) == 0) {
                    dependsOn.add(names.get(j));
                }
            }
            tasks.put(
                    names.get(i),
                    new TaskDefinition(
                            names.get(i),
                            dependsOn,
                            List.of("services/module" + i + "/**"),
                            List.of("build/module" + i + "/**"),
                            List.of("echo", names.get(i)),
                            List.of(),
                            "10m",
                            true));
        }
        return new ForgeConfig(
                1, new ProjectInfo("seed-" + seed), new Defaults("10m", true), tasks);
    }

    /**
     * The same graph with its task declaration order and every {@code depends_on} list shuffled — a
     * different {@code forgeci.yml} spelling of an identical dependency structure.
     */
    static ForgeConfig permute(ForgeConfig config, long seed) {
        Random random = new Random(seed);
        List<String> order = new ArrayList<>(config.tasks().keySet());
        Collections.shuffle(order, random);

        Map<String, TaskDefinition> shuffled = new LinkedHashMap<>();
        for (String name : order) {
            TaskDefinition task = config.tasks().get(name);
            List<String> dependsOn = new ArrayList<>(task.dependsOn());
            Collections.shuffle(dependsOn, random);
            List<String> inputs = new ArrayList<>(task.inputs());
            Collections.shuffle(inputs, random);
            shuffled.put(
                    name,
                    new TaskDefinition(
                            task.name(),
                            dependsOn,
                            inputs,
                            task.outputs(),
                            task.command(),
                            task.environment(),
                            task.timeout(),
                            task.cacheable()));
        }
        return new ForgeConfig(config.version(), config.project(), config.defaults(), shuffled);
    }
}
