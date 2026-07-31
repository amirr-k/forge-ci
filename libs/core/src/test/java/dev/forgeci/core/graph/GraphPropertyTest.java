package dev.forgeci.core.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.core.model.ForgeConfig;
import dev.forgeci.core.plan.BuildPlan;
import dev.forgeci.core.plan.PlanBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Properties that must hold for every graph, not just the hand-built fixtures: each case runs
 * against a fresh seeded random DAG, and the seed is the test's display name, so a failure names
 * the exact input to replay ({@link RandomGraphs#dag}).
 */
class GraphPropertyTest {

    private static LongStream seeds() {
        return LongStream.rangeClosed(1, 300);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void everyEmittedExecutionOrderRespectsDependencies(long seed) {
        TaskGraph graph = TaskGraph.build(RandomGraphs.dag(seed));

        List<String> order = TopologicalSorter.sort(graph);

        assertEquals(graph.taskNames().size(), order.size(), "the order must contain every task exactly once");
        Set<String> alreadyRun = new HashSet<>();
        for (String name : order) {
            for (String dependency : graph.dependenciesOf(name)) {
                assertTrue(alreadyRun.contains(dependency), name + " ran before its dependency " + dependency);
            }
            alreadyRun.add(name);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void permutingHowTheGraphIsDeclaredDoesNotChangeTheBuildPlan(long seed) {
        ForgeConfig config = RandomGraphs.dag(seed);
        Set<String> changedPaths = someChangedPaths(config, seed);

        BuildPlan original = PlanBuilder.forChangedPaths(TaskGraph.build(config), changedPaths);
        BuildPlan permuted = PlanBuilder.forChangedPaths(TaskGraph.build(RandomGraphs.permute(config, seed)), changedPaths);

        assertEquals(original.selected(), permuted.selected(), "selected tasks, order, and reasons must all be permutation-independent");
        assertEquals(original.unaffected(), permuted.unaffected());
        assertEquals(original.changedPaths(), permuted.changedPaths());
    }

    /** A random subset of the paths the generated tasks declare as inputs, so some tasks are hit and some are not. */
    private static Set<String> someChangedPaths(ForgeConfig config, long seed) {
        Random random = new Random(~seed);
        Set<String> changed = new HashSet<>();
        IntStream.range(0, config.tasks().size())
                .filter(i -> random.nextBoolean())
                .forEach(i -> changed.add("services/module" + i + "/src/Main.java"));
        return changed;
    }
}
