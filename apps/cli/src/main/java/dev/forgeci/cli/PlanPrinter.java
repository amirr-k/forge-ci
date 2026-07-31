package dev.forgeci.cli;

import dev.forgeci.core.graph.AffectedTask;
import dev.forgeci.core.plan.BuildPlan;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Renders a {@link BuildPlan} in the format {@code forge plan} is specified to print. */
final class PlanPrinter {

    private static final String RUN_ROW = "  %-24s %-8s %s%n";
    private static final String CACHED_ROW = "  %-24s %s%n";

    private PlanPrinter() {}

    static void print(
            PrintWriter out, BuildPlan plan, Map<String, CacheCoordinator.Decision> decisions) {
        out.println("ForgeCI plan");
        out.println();

        if (plan.fullBuild()) {
            out.println("Full build requested — every task is selected");
        } else {
            out.println("Changed files");
            if (plan.changedPaths().isEmpty()) {
                out.println("  (none)");
            } else {
                for (String path : plan.changedPaths()) {
                    out.println("  " + path);
                }
            }
        }

        List<AffectedTask> toRun = new ArrayList<>();
        List<AffectedTask> reused = new ArrayList<>();
        for (AffectedTask task : plan.selected()) {
            CacheCoordinator.Decision decision = decisions.get(task.name());
            if (decision != null && decision.hit()) {
                reused.add(task);
            } else {
                toRun.add(task);
            }
        }

        if (!toRun.isEmpty()) {
            out.println();
            out.println("Affected tasks");
            for (AffectedTask task : toRun) {
                out.printf(RUN_ROW, task.name(), "RUN", task.reason());
            }
        }

        if (!reused.isEmpty()) {
            out.println();
            out.println("Reused tasks");
            for (AffectedTask task : reused) {
                out.printf(CACHED_ROW, task.name(), "CACHED");
            }
        }

        out.println();
        out.printf(
                "Plan: %d run, %d cached, %d unaffected%n",
                toRun.size(), reused.size(), plan.unaffected().size());
    }
}
