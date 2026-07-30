package dev.forgeci.core.plan;

import dev.forgeci.core.graph.AffectedResult;
import dev.forgeci.core.graph.AffectedTask;
import dev.forgeci.core.graph.AffectedTaskAnalyzer;
import dev.forgeci.core.graph.TaskGraph;
import dev.forgeci.core.graph.TopologicalSorter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns a changed-path set into an ordered {@link BuildPlan}. */
public final class PlanBuilder {

    public static final String CONFIG_FILE = "forgeci.yml";

    private PlanBuilder() {}

    public static BuildPlan forChangedPaths(TaskGraph graph, Set<String> changedPaths) {
        // safe invalidation: a changed forgeci.yml can alter any task's command, inputs, or edges,
        // so rebuild everything rather than guess which tasks the edit affected
        if (changedPaths.contains(CONFIG_FILE)) {
            return selectAll(graph, changedPaths, CONFIG_FILE + " changed", false);
        }

        AffectedResult result = AffectedTaskAnalyzer.analyze(graph, changedPaths);
        Map<String, String> reasons = new LinkedHashMap<>();
        for (AffectedTask task : result.affected()) {
            reasons.put(task.name(), task.reason());
        }

        List<AffectedTask> selected = new ArrayList<>(reasons.size());
        for (String name : TopologicalSorter.sort(graph)) {
            String reason = reasons.get(name);
            if (reason != null) {
                selected.add(new AffectedTask(name, reason));
            }
        }
        return new BuildPlan(List.copyOf(changedPaths), selected, result.unaffected(), false);
    }

    /** Every task, for a first build or an explicitly requested full rebuild. */
    public static BuildPlan fullBuild(TaskGraph graph) {
        return selectAll(graph, Set.of(), "full build requested", true);
    }

    private static BuildPlan selectAll(
            TaskGraph graph, Set<String> changedPaths, String reason, boolean fullBuild) {
        List<AffectedTask> selected = new ArrayList<>(graph.size());
        for (String name : TopologicalSorter.sort(graph)) {
            selected.add(new AffectedTask(name, reason));
        }
        return new BuildPlan(List.copyOf(changedPaths), selected, List.of(), fullBuild);
    }
}
