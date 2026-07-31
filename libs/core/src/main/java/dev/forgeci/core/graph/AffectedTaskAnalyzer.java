package dev.forgeci.core.graph;

import dev.forgeci.core.glob.GlobMatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes the affected-task closure for a changed-path set: tasks whose declared inputs match a
 * changed path directly, plus every task reachable by following dependents from there (their output
 * may depend on the changed task's output).
 */
public final class AffectedTaskAnalyzer {

    private AffectedTaskAnalyzer() {}

    public static AffectedResult analyze(TaskGraph graph, Set<String> changedPaths) {
        // task -> upstream task that pulled it in transitively; absent means directly affected
        Map<String, String> via = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();

        for (String name : new TreeSet<>(graph.taskNames())) {
            if (matchesAnyInput(graph, name, changedPaths)) {
                via.put(name, null);
                queue.add(name);
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String dependent : graph.dependentsOf(current)) {
                if (!via.containsKey(dependent)) {
                    via.put(dependent, current);
                    queue.add(dependent);
                }
            }
        }

        List<AffectedTask> affected = new ArrayList<>();
        for (String name : new TreeSet<>(via.keySet())) {
            String upstream = via.get(name);
            String reason = upstream == null ? "source changed" : upstream + " output may change";
            affected.add(new AffectedTask(name, reason));
        }

        List<String> unaffected = new ArrayList<>();
        for (String name : new TreeSet<>(graph.taskNames())) {
            if (!via.containsKey(name)) {
                unaffected.add(name);
            }
        }

        return new AffectedResult(affected, unaffected);
    }

    private static boolean matchesAnyInput(
            TaskGraph graph, String taskName, Set<String> changedPaths) {
        List<String> inputs = graph.task(taskName).inputs();
        for (String changedPath : changedPaths) {
            for (String pattern : inputs) {
                if (GlobMatcher.matches(pattern, changedPath)) {
                    return true;
                }
            }
        }
        return false;
    }
}
