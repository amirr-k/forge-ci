package dev.forgeci.core.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CycleDetectorTest {

    @Test
    void findsNoCycleInAcyclicGraph() {
        TaskGraph graph = TaskGraph.build(GraphFixtures.demoConfig());

        assertEquals(Optional.empty(), CycleDetector.findCycle(graph));
    }

    @Test
    void findsCycleAndReportsExactPath() {
        TaskGraph graph = TaskGraph.build(GraphFixtures.cyclicConfig());

        Optional<List<String>> cycle = CycleDetector.findCycle(graph);

        assertTrue(cycle.isPresent());
        assertEquals(List.of("frontend:build", "api:generate", "frontend:build"), cycle.get());
    }

    @Test
    void cycleDetectedExceptionMatchesSpecFormat() {
        CycleDetectedException exception =
                new CycleDetectedException(List.of("frontend:build", "api:generate", "frontend:build"));

        assertEquals(
                "Cycle detected:\nfrontend:build -> api:generate -> frontend:build",
                exception.getMessage());
    }
}
