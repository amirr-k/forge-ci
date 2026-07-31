package dev.forgeci.cli;

import dev.forgeci.cache.CacheKey;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Explains why a task would run or be reused: its cache key, each contributor, and the hit/miss
 * reason.
 */
@Command(name = "explain", description = "Explain why a task would run or reuse its cache.")
final class ExplainCommand implements Callable<Integer> {

    private static final String CONTRIBUTOR_ROW = "  %-22s%s%n";

    @Parameters(index = "0", paramLabel = "<task>", description = "The task to explain.")
    private String taskName;

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        ProjectWorkspace workspace = ProjectWorkspace.load();
        if (!workspace.graph().contains(taskName)) {
            throw new CliException(
                    "no such task: '"
                            + taskName
                            + "'. Run 'forge plan' to see the tasks this project declares.");
        }

        CacheCoordinator coordinator = new CacheCoordinator(workspace);
        CacheCoordinator.Decision decision = coordinator.decide(taskName);
        CacheKey key = decision.key();

        PrintWriter out = spec.commandLine().getOut();
        out.println("Cache key: sha256:" + key.value());
        out.println();
        out.println("Contributors");
        out.printf(CONTRIBUTOR_ROW, "task definition", "sha256:" + key.taskDefinitionDigest());
        out.printf(CONTRIBUTOR_ROW, "source inputs", "sha256:" + key.sourceInputsDigest());
        out.printf(
                CONTRIBUTOR_ROW,
                "dependency artifacts",
                "sha256:" + key.dependencyArtifactsDigest());
        out.printf(CONTRIBUTOR_ROW, "toolchain", key.toolchain());
        out.println();
        if (decision.hit()) {
            out.println("Result: cache hit");
        } else {
            out.println("Result: cache miss");
            out.println("Reason: " + decision.reason());
        }
        out.flush();
        return ExitCode.SUCCESS;
    }
}
