package dev.forgeci.core.graph;

import dev.forgeci.core.model.ForgeConfig;
import dev.forgeci.core.model.TaskDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The task dependency DAG built from a {@link ForgeConfig}. Construction assumes every {@code
 * depends_on} reference has already been validated against the task set (the config parser's job) —
 * a dangling reference here is a programming-contract violation, not a user error.
 */
public final class TaskGraph {

    private final Map<String, TaskDefinition> tasks;
    private final Map<String, List<String>> dependents;

    private TaskGraph(Map<String, TaskDefinition> tasks, Map<String, List<String>> dependents) {
        this.tasks = tasks;
        this.dependents = dependents;
    }

    public static TaskGraph build(ForgeConfig config) {
        Map<String, TaskDefinition> tasks = new LinkedHashMap<>(config.tasks());
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (String name : tasks.keySet()) {
            dependents.put(name, new ArrayList<>());
        }
        for (TaskDefinition task : tasks.values()) {
            for (String dependency : task.dependsOn()) {
                if (!tasks.containsKey(dependency)) {
                    throw new IllegalArgumentException(
                            "task '"
                                    + task.name()
                                    + "' depends on undefined task '"
                                    + dependency
                                    + "' — this should have been rejected during config"
                                    + " validation");
                }
                dependents.get(dependency).add(task.name());
            }
        }
        return new TaskGraph(
                Collections.unmodifiableMap(tasks), Collections.unmodifiableMap(dependents));
    }

    public Set<String> taskNames() {
        return tasks.keySet();
    }

    public boolean contains(String name) {
        return tasks.containsKey(name);
    }

    public TaskDefinition task(String name) {
        TaskDefinition task = tasks.get(name);
        if (task == null) {
            throw new IllegalArgumentException("no such task: " + name);
        }
        return task;
    }

    /** Tasks that {@code name} directly depends on. */
    public List<String> dependenciesOf(String name) {
        return task(name).dependsOn();
    }

    /** Tasks that directly depend on {@code name}. */
    public List<String> dependentsOf(String name) {
        List<String> result = dependents.get(name);
        if (result == null) {
            throw new IllegalArgumentException("no such task: " + name);
        }
        return result;
    }

    public int size() {
        return tasks.size();
    }
}
