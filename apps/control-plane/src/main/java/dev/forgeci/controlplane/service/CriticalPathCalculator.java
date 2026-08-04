package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.TaskDefinitionEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Per-task "remaining critical path" within one build's selected task set: the longest chain of
 * still-to-run tasks from this task to a sink (a task nothing else in the selected set depends on).
 * The scheduler releases the highest-weight ready task first so work feeding the longest remaining
 * chain starts as early as possible — see spec/reference/architecture.md#scheduler.
 *
 * <p>Two measures, because they disagree and the disagreement is the point. {@link #weights} counts
 * hops, which costs nothing but treats a long chain of trivial tasks as more urgent than a short
 * chain of expensive ones. {@link #millis} measures the same chain in estimated milliseconds, which
 * ranks correctly when task costs are uneven but is only as good as the estimates it is handed.
 */
final class CriticalPathCalculator {

    private CriticalPathCalculator() {}

    static Map<String, Integer> weights(List<TaskDefinitionEntity> tasks) {
        Map<String, List<String>> dependents = dependents(tasks);
        Map<String, Integer> memo = new HashMap<>();
        for (TaskDefinitionEntity task : tasks) {
            hops(task.getTaskName(), dependents, memo);
        }
        return memo;
    }

    /**
     * @param estimate observed-duration estimate in milliseconds for a task name; the caller
     *     supplies the fallback for names with no history, since a task that has never run still
     *     has to be ordered against ones that have
     */
    static Map<String, Long> millis(
            List<TaskDefinitionEntity> tasks, ToLongFunction<String> estimate) {
        Map<String, List<String>> dependents = dependents(tasks);
        Map<String, Long> memo = new HashMap<>();
        for (TaskDefinitionEntity task : tasks) {
            duration(task.getTaskName(), dependents, estimate, memo);
        }
        return memo;
    }

    private static Map<String, List<String>> dependents(List<TaskDefinitionEntity> tasks) {
        Map<String, List<String>> dependents = new HashMap<>();
        for (TaskDefinitionEntity task : tasks) {
            dependents.putIfAbsent(task.getTaskName(), new ArrayList<>());
        }
        for (TaskDefinitionEntity task : tasks) {
            for (String dependency : task.getDependsOn()) {
                dependents
                        .computeIfAbsent(dependency, d -> new ArrayList<>())
                        .add(task.getTaskName());
            }
        }
        return dependents;
    }

    private static int hops(
            String name, Map<String, List<String>> dependents, Map<String, Integer> memo) {
        Integer cached = memo.get(name);
        if (cached != null) {
            return cached;
        }
        int max = 0;
        for (String dependent : dependents.getOrDefault(name, List.of())) {
            max = Math.max(max, hops(dependent, dependents, memo));
        }
        int result = max + 1;
        memo.put(name, result);
        return result;
    }

    private static long duration(
            String name,
            Map<String, List<String>> dependents,
            ToLongFunction<String> estimate,
            Map<String, Long> memo) {
        Long cached = memo.get(name);
        if (cached != null) {
            return cached;
        }
        long max = 0;
        for (String dependent : dependents.getOrDefault(name, List.of())) {
            max = Math.max(max, duration(dependent, dependents, estimate, memo));
        }
        long result = max + Math.max(0, estimate.applyAsLong(name));
        memo.put(name, result);
        return result;
    }
}
