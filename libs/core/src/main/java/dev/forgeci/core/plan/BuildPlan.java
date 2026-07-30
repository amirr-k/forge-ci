package dev.forgeci.core.plan;

import dev.forgeci.core.graph.AffectedTask;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * What a run would do: which tasks are selected and why, and which are untouched.
 *
 * @param changedPaths project-relative changed paths the plan was derived from, sorted
 * @param selected tasks to execute, in topological order (dependencies first)
 * @param unaffected tasks no change reaches, sorted alphabetically
 * @param fullBuild true when every task was selected on purpose rather than by change analysis
 */
public record BuildPlan(
        List<String> changedPaths, List<AffectedTask> selected, List<String> unaffected, boolean fullBuild) {

    public BuildPlan {
        changedPaths = List.copyOf(new TreeSet<>(changedPaths));
        selected = List.copyOf(selected);
        unaffected = List.copyOf(unaffected);
    }

    /** Selected task names, in topological order — the input {@code LocalExecutor} expects. */
    public List<String> selectedTaskNames() {
        List<String> names = new ArrayList<>(selected.size());
        for (AffectedTask task : selected) {
            names.add(task.name());
        }
        return names;
    }

    public boolean isEmpty() {
        return selected.isEmpty();
    }
}
