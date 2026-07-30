package dev.forgeci.core.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TopologicalSorterTest {

    @Test
    void ordersDependenciesBeforeDependents() {
        TaskGraph graph = TaskGraph.build(GraphFixtures.demoConfig());

        List<String> order = TopologicalSorter.sort(graph);

        assertEquals(6, order.size());
        assertTrue(order.indexOf("pricing:test") < order.indexOf("pricing:build"));
        assertTrue(order.indexOf("pricing:build") < order.indexOf("checkout:integration"));
    }

    @Test
    void breaksTiesAlphabeticallyForDeterminism() {
        TaskGraph graph = TaskGraph.build(GraphFixtures.demoConfig());

        // all of these are independent (no deps among each other), so they must come out sorted
        List<String> order = TopologicalSorter.sort(graph);
        List<String> roots =
                order.stream()
                        .filter(name -> List.of("accounts:test", "catalog:build", "pricing:test").contains(name))
                        .toList();

        assertEquals(List.of("accounts:test", "catalog:build", "pricing:test"), roots);
    }

    @Test
    void throwsCycleDetectedExceptionForCyclicGraph() {
        TaskGraph graph = TaskGraph.build(GraphFixtures.cyclicConfig());

        CycleDetectedException exception =
                assertThrows(CycleDetectedException.class, () -> TopologicalSorter.sort(graph));
        assertEquals(
                "Cycle detected:\nfrontend:build -> api:generate -> frontend:build",
                exception.getMessage());
    }
}
