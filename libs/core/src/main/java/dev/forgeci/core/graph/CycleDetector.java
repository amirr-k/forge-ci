package dev.forgeci.core.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Depth-first cycle detection over a {@link TaskGraph}'s dependency edges. */
public final class CycleDetector {

    private enum Color {
        WHITE,
        GRAY,
        BLACK
    }

    private CycleDetector() {}

    /** Returns the first cycle found (as a closed path), if any. Deterministic across runs. */
    public static Optional<List<String>> findCycle(TaskGraph graph) {
        Map<String, Color> color = new HashMap<>();
        for (String name : graph.taskNames()) {
            color.put(name, Color.WHITE);
        }
        Deque<String> path = new ArrayDeque<>();

        // forgeci.yml declaration order keeps the reported cycle deterministic and matches the
        // task order a human reading the file would expect
        for (String start : graph.taskNames()) {
            if (color.get(start) == Color.WHITE) {
                List<String> cycle = visit(graph, start, color, path);
                if (cycle != null) {
                    return Optional.of(cycle);
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> visit(
            TaskGraph graph, String node, Map<String, Color> color, Deque<String> path) {
        color.put(node, Color.GRAY);
        path.push(node);

        for (String dependency : graph.dependenciesOf(node)) {
            Color state = color.get(dependency);
            if (state == Color.GRAY) {
                return closedCycleFrom(path, dependency);
            }
            if (state == Color.WHITE) {
                List<String> cycle = visit(graph, dependency, color, path);
                if (cycle != null) {
                    return cycle;
                }
            }
        }

        path.pop();
        color.put(node, Color.BLACK);
        return null;
    }

    private static List<String> closedCycleFrom(Deque<String> path, String repeated) {
        List<String> cycle = new ArrayList<>();
        cycle.add(repeated);
        for (String node : path) {
            cycle.add(node);
            if (node.equals(repeated)) {
                break;
            }
        }
        Collections.reverse(cycle);
        return cycle;
    }
}
