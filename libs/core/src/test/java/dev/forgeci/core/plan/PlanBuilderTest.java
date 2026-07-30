package dev.forgeci.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.core.graph.GraphFixtures;
import dev.forgeci.core.graph.TaskGraph;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanBuilderTest {

    private final TaskGraph graph = TaskGraph.build(GraphFixtures.demoConfig());

    @Test
    void selectsNothingWhenNothingChanged() {
        BuildPlan plan = PlanBuilder.forChangedPaths(graph, Set.of());

        assertTrue(plan.isEmpty());
        assertEquals(graph.size(), plan.unaffected().size());
    }

    @Test
    void selectsOnlyTheAffectedClosureForALeafChange() {
        BuildPlan plan =
                PlanBuilder.forChangedPaths(graph, Set.of("services/accounts/src/AccountService.java"));

        assertEquals(List.of("accounts:test"), plan.selectedTaskNames());
        assertEquals("source changed", plan.selected().get(0).reason());
        assertTrue(plan.unaffected().contains("catalog:build"));
    }

    @Test
    void ordersSelectedTasksDependenciesFirst() {
        BuildPlan plan =
                PlanBuilder.forChangedPaths(graph, Set.of("services/pricing/src/PriceCalculator.java"));

        assertEquals(
                List.of("pricing:test", "pricing:build", "checkout:integration"), plan.selectedTaskNames());
        // pricing declares the changed path directly; checkout only inherits it through the graph
        assertEquals("source changed", plan.selected().get(1).reason());
        assertEquals("pricing:build output may change", plan.selected().get(2).reason());
    }

    @Test
    void selectsEverythingWhenTheConfigurationItselfChanged() {
        BuildPlan plan = PlanBuilder.forChangedPaths(graph, Set.of("forgeci.yml"));

        assertEquals(graph.size(), plan.selected().size());
        assertEquals(List.of(), plan.unaffected());
        assertEquals("forgeci.yml changed", plan.selected().get(0).reason());
        // an explicit config change is not the same thing as an operator asking for a full build
        assertFalse(plan.fullBuild());
    }

    @Test
    void fullBuildSelectsEveryTaskInDependencyOrder() {
        BuildPlan plan = PlanBuilder.fullBuild(graph);

        assertTrue(plan.fullBuild());
        assertEquals(graph.size(), plan.selected().size());
        List<String> names = plan.selectedTaskNames();
        assertTrue(
                names.indexOf("pricing:test") < names.indexOf("pricing:build"), "unexpected order: " + names);
    }

    @Test
    void listsChangedPathsSorted() {
        BuildPlan plan =
                PlanBuilder.forChangedPaths(
                        graph, Set.of("services/pricing/b.java", "services/pricing/a.java"));

        assertEquals(List.of("services/pricing/a.java", "services/pricing/b.java"), plan.changedPaths());
    }
}
