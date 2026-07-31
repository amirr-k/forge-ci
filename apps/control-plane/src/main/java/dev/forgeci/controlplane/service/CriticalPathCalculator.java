package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.TaskDefinitionEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-task "remaining critical path" weight within one build's selected task set: the length of the
 * longest chain of still-to-run tasks from this task to a sink (a task nothing else in the selected
 * set depends on). The scheduler releases the highest-weight ready task first so work feeding the
 * longest remaining chain starts as early as possible — see
 * spec/reference/architecture.md#scheduler.
 */
final class CriticalPathCalculator {

    private CriticalPathCalculator() {}

    static Map<String, Integer> weights(List<TaskDefinitionEntity> tasks) {
        Map<String, TaskDefinitionEntity> byName = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (TaskDefinitionEntity task : tasks) {
            byName.put(task.getTaskName(), task);
            dependents.putIfAbsent(task.getTaskName(), new java.util.ArrayList<>());
        }
        for (TaskDefinitionEntity task : tasks) {
            for (String dependency : task.getDependsOn()) {
                dependents
                        .computeIfAbsent(dependency, d -> new java.util.ArrayList<>())
                        .add(task.getTaskName());
            }
        }

        Map<String, Integer> memo = new HashMap<>();
        for (String name : byName.keySet()) {
            weight(name, dependents, memo);
        }
        return memo;
    }

    private static int weight(
            String name, Map<String, List<String>> dependents, Map<String, Integer> memo) {
        Integer cached = memo.get(name);
        if (cached != null) {
            return cached;
        }
        int max = 0;
        for (String dependent : dependents.getOrDefault(name, List.of())) {
            max = Math.max(max, weight(dependent, dependents, memo));
        }
        int result = max + 1;
        memo.put(name, result);
        return result;
    }
}
