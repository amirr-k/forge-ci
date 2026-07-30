package dev.forgeci.cli;

import dev.forgeci.config.ForgeConfigParser;
import dev.forgeci.core.graph.AffectedResult;
import dev.forgeci.core.graph.AffectedTask;
import dev.forgeci.core.graph.AffectedTaskAnalyzer;
import dev.forgeci.core.graph.CycleDetectedException;
import dev.forgeci.core.graph.TaskGraph;
import dev.forgeci.core.graph.TopologicalSorter;
import dev.forgeci.core.model.ForgeConfig;
import dev.forgeci.core.validation.ConfigValidationException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Validates the graph, matches a static changed-path set against declared task inputs, and
 * prints the affected-task closure. Performs no execution — that's {@code forge run} (phase 1).
 */
@Command(name = "plan", description = "Print the affected-task closure for a set of changed files.")
public final class PlanCommand implements Callable<Integer> {

    @Option(
            names = "--changed",
            required = true,
            description = "A changed file path, relative to the repository root. Repeatable.")
    private String[] changed = new String[0];

    @Override
    public Integer call() {
        PrintWriter out = new PrintWriter(System.out, true);
        PrintWriter err = new PrintWriter(System.err, true);

        // consult user.dir live (rather than Path.of's cached process cwd) so tests can redirect it
        Path forgeciYml = Path.of(System.getProperty("user.dir"), "forgeci.yml");
        ForgeConfig config;
        TaskGraph graph;
        try {
            config = ForgeConfigParser.parse(forgeciYml);
            graph = TaskGraph.build(config);
            TopologicalSorter.sort(graph);
        } catch (ConfigValidationException | CycleDetectedException e) {
            err.println(e.getMessage());
            return 1;
        }

        Set<String> changedPaths = new LinkedHashSet<>(java.util.List.of(changed));
        AffectedResult result = AffectedTaskAnalyzer.analyze(graph, changedPaths);

        printPlan(out, changedPaths, result);
        return 0;
    }

    private void printPlan(PrintWriter out, Set<String> changedPaths, AffectedResult result) {
        out.println("ForgeCI plan");
        out.println();

        out.println("Changed files");
        for (String path : changedPaths) {
            out.println("  " + path);
        }

        int nameWidth =
                result.affected().stream().mapToInt(task -> task.name().length()).max().orElse(0);

        if (!result.affected().isEmpty()) {
            out.println();
            out.println("Affected tasks");
            for (AffectedTask task : result.affected()) {
                out.printf("  %-" + nameWidth + "s  RUN      %s%n", task.name(), task.reason());
            }
        }

        if (!result.unaffected().isEmpty()) {
            out.println();
            out.println("Unaffected tasks");
            for (String name : result.unaffected()) {
                out.println("  " + name);
            }
        }

        out.println();
        out.printf(
                "Plan: %d affected, %d unaffected%n", result.affected().size(), result.unaffected().size());
    }
}
