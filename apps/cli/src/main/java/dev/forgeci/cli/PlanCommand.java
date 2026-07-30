package dev.forgeci.cli;

import dev.forgeci.core.graph.AffectedTask;
import dev.forgeci.core.plan.BuildPlan;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Validates the graph, reads changed paths from Git, and prints which tasks a run would execute.
 * Performs no execution — that is {@code forge run}. A task the change analysis flagged as affected
 * can still turn out to have a verified cache hit; those are reported as reused rather than run.
 */
@Command(name = "plan", description = "Show which tasks the current changes affect. Runs nothing.")
final class PlanCommand implements Callable<Integer> {

    @Mixin private SelectionOptions selection;

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        ProjectWorkspace workspace = ProjectWorkspace.load();
        BuildPlan plan = selection.resolvePlan(workspace);

        CacheCoordinator coordinator = new CacheCoordinator(workspace);
        Map<String, CacheCoordinator.Decision> decisions = new LinkedHashMap<>();
        for (AffectedTask task : plan.selected()) {
            CacheCoordinator.Decision decision = coordinator.decide(task.name());
            decisions.put(task.name(), decision);
            if (!decision.hit()) {
                coordinator.recordPending(task.name());
            }
        }

        PrintWriter out = spec.commandLine().getOut();
        PlanPrinter.print(out, plan, decisions);
        out.flush();
        return ExitCode.SUCCESS;
    }
}
