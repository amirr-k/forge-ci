package dev.forgeci.core.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AffectedTaskAnalyzerTest {

    @Test
    void directHitAndTransitiveDependentsAreAffected() {
        TaskGraph graph = TaskGraph.build(GraphFixtures.demoConfig());

        AffectedResult result =
                AffectedTaskAnalyzer.analyze(
                        graph, Set.of("services/pricing/src/main/java/PriceCalculator.java"));

        assertEquals(
                List.of(
                        new AffectedTask("checkout:integration", "pricing:build output may change"),
                        new AffectedTask("pricing:build", "source changed"),
                        new AffectedTask("pricing:test", "source changed")),
                result.affected());
        assertEquals(
                List.of("accounts:test", "catalog:build", "storefront:build"), result.unaffected());
    }

    @Test
    void noMatchesLeavesEverythingUnaffected() {
        TaskGraph graph = TaskGraph.build(GraphFixtures.demoConfig());

        AffectedResult result = AffectedTaskAnalyzer.analyze(graph, Set.of("unrelated/path.txt"));

        assertEquals(List.of(), result.affected());
        assertEquals(6, result.unaffected().size());
    }

    @Test
    void changeAtRootOfGraphOnlyAffectsThatTask() {
        TaskGraph graph = TaskGraph.build(GraphFixtures.demoConfig());

        AffectedResult result =
                AffectedTaskAnalyzer.analyze(graph, Set.of("services/catalog/src/Main.java"));

        assertEquals(List.of(new AffectedTask("catalog:build", "source changed")), result.affected());
    }
}
