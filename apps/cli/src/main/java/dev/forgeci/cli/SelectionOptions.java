package dev.forgeci.cli;

import dev.forgeci.core.git.GitWorkspace;
import dev.forgeci.core.plan.BuildPlan;
import dev.forgeci.core.plan.PlanBuilder;
import picocli.CommandLine.Option;

/** How {@code plan} and {@code run} decide which tasks to select. */
final class SelectionOptions {

    @Option(
            names = "--base",
            paramLabel = "<revision>",
            description = "Git revision to compare against (default: ${DEFAULT-VALUE}).")
    private String base = GitWorkspace.DEFAULT_BASE_REVISION;

    @Option(
            names = "--all",
            description = "Select every task, as for a first build with nothing to reuse.")
    private boolean all;

    BuildPlan resolvePlan(ProjectWorkspace workspace) {
        if (all) {
            return PlanBuilder.fullBuild(workspace.graph());
        }
        GitWorkspace git = GitWorkspace.discover(workspace.directory());
        return PlanBuilder.forChangedPaths(workspace.graph(), git.changedPaths(base));
    }
}
