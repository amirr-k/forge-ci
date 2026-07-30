package dev.forgeci.cli;

import dev.forgeci.core.graph.AffectedTask;
import dev.forgeci.core.plan.BuildPlan;
import java.io.PrintWriter;

/** Renders a {@link BuildPlan} in the format {@code forge plan} is specified to print. */
final class PlanPrinter {

    private static final String TASK_ROW = "  %-24s %-8s %s%n";

    private PlanPrinter() {}

    static void print(PrintWriter out, BuildPlan plan) {
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

        if (!plan.selected().isEmpty()) {
            out.println();
            out.println("Affected tasks");
            for (AffectedTask task : plan.selected()) {
                out.printf(TASK_ROW, task.name(), "RUN", task.reason());
            }
        }

        // no "Reused tasks" section yet: reuse needs the content-addressed cache from phase 2, and
        // reporting a cache hit ForgeCI has not verified would be a lie
        out.println();
        out.printf(
                "Plan: %d run, 0 cached, %d unaffected%n", plan.selected().size(), plan.unaffected().size());
    }
}
