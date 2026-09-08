package dev.cnba.core.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cnba.core.model.Defaults;
import dev.cnba.core.model.CNBAConfig;
import dev.cnba.core.model.ProjectInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskGraphTest {

    @Test
    void buildsForwardAndReverseAdjacency() {
        TaskGraph graph = TaskGraph.build(GraphFixtures.demoConfig());

        assertEquals(6, graph.size());
        assertEquals(List.of("pricing:test"), graph.dependenciesOf("pricing:build"));
        assertEquals(List.of("pricing:build"), graph.dependentsOf("pricing:test"));
        assertTrue(graph.dependenciesOf("catalog:build").isEmpty());
        assertTrue(graph.dependentsOf("checkout:integration").isEmpty());
    }

    @Test
    void containsReflectsDeclaredTasks() {
        TaskGraph graph = TaskGraph.build(GraphFixtures.demoConfig());

        assertTrue(graph.contains("pricing:build"));
        assertTrue(!graph.contains("nonexistent:task"));
    }

    @Test
    void rejectsDanglingDependencyReferenceDefensively() {
        Map<String, dev.cnba.core.model.TaskDefinition> tasks = new LinkedHashMap<>();
        tasks.put("a", GraphFixtures.task("a", List.of("b"), List.of()));
        CNBAConfig config =
                new CNBAConfig(1, new ProjectInfo("demo"), new Defaults("10m", true), tasks);

        assertThrows(IllegalArgumentException.class, () -> TaskGraph.build(config));
    }
}
