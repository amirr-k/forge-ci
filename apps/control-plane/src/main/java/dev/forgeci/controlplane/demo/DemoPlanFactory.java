package dev.forgeci.controlplane.demo;

import dev.forgeci.cache.CacheKey;
import dev.forgeci.cache.CacheKeyCalculator;
import dev.forgeci.config.ForgeConfigParser;
import dev.forgeci.controlplane.api.dto.TaskDefinitionRequest;
import dev.forgeci.core.exec.Durations;
import dev.forgeci.core.graph.AffectedTask;
import dev.forgeci.core.graph.TaskGraph;
import dev.forgeci.core.graph.TopologicalSorter;
import dev.forgeci.core.model.ForgeConfig;
import dev.forgeci.core.model.TaskDefinition;
import dev.forgeci.core.plan.BuildPlan;
import dev.forgeci.core.plan.PlanBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Turns a scenario-mutated demo-repo working copy into the plan a guest build submits: which
 * tasks run (real, content-derived cache keys), which are structurally unaffected and never
 * scheduled at all (the honest "reused previous output" case — nothing server-side short-circuits
 * an affected task's execution, so "reused" only ever means "not part of this build"), and the
 * exact command each affected task runs.
 */
@Component
public class DemoPlanFactory {

    /** Fixed rather than the host JVM's real toolchain — the demo has no notion of a build machine's own JDK. */
    private static final String TOOLCHAIN = "forge-demo-v1";

    public DemoPlan build(Path workspace, DemoScenario scenario) {
        return build(workspace, scenario, false);
    }

    /** The one server-driven warm-up build: every task really executes once, so "reused previous output" is honest from the first guest visit onward. */
    public DemoPlan buildFull(Path workspace, DemoScenario baselineScenario) {
        return build(workspace, baselineScenario, true);
    }

    private DemoPlan build(Path workspace, DemoScenario scenario, boolean full) {
        ForgeConfig config = ForgeConfigParser.parse(workspace.resolve("forgeci.yml"));
        TaskGraph graph = TaskGraph.build(config);
        BuildPlan plan = full ? PlanBuilder.fullBuild(graph) : PlanBuilder.forChangedPaths(graph, scenario.changedPaths());

        Map<String, String> reasons = new LinkedHashMap<>();
        for (AffectedTask task : plan.selected()) {
            reasons.put(task.name(), task.reason());
        }

        Map<String, String> digestByTask = new LinkedHashMap<>();
        List<TaskDefinitionRequest> tasks = new ArrayList<>();
        for (String name : TopologicalSorter.sort(graph)) {
            TaskDefinition task = graph.task(name);
            Map<String, String> dependencyDigests = new TreeMap<>();
            for (String dependency : task.dependsOn()) {
                dependencyDigests.put(dependency, digestByTask.get(dependency));
            }
            CacheKey key = CacheKeyCalculator.compute(workspace, task, Map.of(), dependencyDigests, TOOLCHAIN);
            digestByTask.put(name, key.value());

            String reason = reasons.get(name);
            if (reason != null) {
                tasks.add(
                        new TaskDefinitionRequest(
                                name,
                                task.dependsOn(),
                                key.value(),
                                reason,
                                scenarioCommand(scenario, task),
                                task.outputs(),
                                List.of(),
                                (int) Durations.parse(task.timeout()).toSeconds()));
            }
        }

        return new DemoPlan(plan.changedPaths(), tasks, plan.unaffected());
    }

    /** Every task first applies the same scenario the control plane just hashed against, then runs for real. */
    private static List<String> scenarioCommand(DemoScenario scenario, TaskDefinition task) {
        String original = String.join(" ", task.command());
        return List.of("/bin/sh", "-c", "./scripts/apply-scenario " + scenario.scriptId() + " && " + original);
    }

    public record DemoPlan(List<String> changedPaths, List<TaskDefinitionRequest> tasks, List<String> unaffectedTasks) {}
}
