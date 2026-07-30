package dev.forgeci.cli;

import dev.forgeci.core.plan.BuildPlan;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Validates the graph, reads changed paths from Git, and prints which tasks a run would execute.
 * Performs no execution — that is {@code forge run}.
 */
@Command(name = "plan", description = "Show which tasks the current changes affect. Runs nothing.")
final class PlanCommand implements Callable<Integer> {

    @Mixin private SelectionOptions selection;

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        ProjectWorkspace workspace = ProjectWorkspace.load();
        BuildPlan plan = selection.resolvePlan(workspace);

        PrintWriter out = spec.commandLine().getOut();
        PlanPrinter.print(out, plan);
        out.flush();
        return ExitCode.SUCCESS;
    }
}
