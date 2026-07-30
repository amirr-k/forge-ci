package dev.forgeci.core.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Kahn's-algorithm topological ordering of a {@link TaskGraph}, dependencies before dependents. */
public final class TopologicalSorter {

    private TopologicalSorter() {}

    /**
     * Returns task names ordered so every task appears after all of its dependencies. Ties are
     * broken alphabetically for a deterministic result.
     *
     * @throws CycleDetectedException if the graph is not a DAG
     */
    public static List<String> sort(TaskGraph graph) {
        Map<String, Integer> remainingDependencies = new HashMap<>();
        for (String name : graph.taskNames()) {
            remainingDependencies.put(name, graph.dependenciesOf(name).size());
        }

        TreeSet<String> ready = new TreeSet<>();
        remainingDependencies.forEach(
                (name, count) -> {
                    if (count == 0) {
                        ready.add(name);
                    }
                });

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String next = ready.pollFirst();
            order.add(next);
            for (String dependent : graph.dependentsOf(next)) {
                int remaining = remainingDependencies.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (order.size() != graph.size()) {
            throw CycleDetector.findCycle(graph)
                    .map(CycleDetectedException::new)
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "topological sort left unresolved tasks but no cycle was"
                                                    + " found — this indicates a graph construction bug"));
        }
        return order;
    }
}
