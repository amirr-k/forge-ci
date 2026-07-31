package dev.forgeci.cli;

import dev.forgeci.core.exec.Durations;
import dev.forgeci.core.exec.ExecutionReport;
import dev.forgeci.core.exec.LocalExecutor;
import dev.forgeci.core.exec.ProcessTaskRunner;
import dev.forgeci.core.exec.TaskOutcome;
import dev.forgeci.core.exec.TaskStatus;
import dev.forgeci.core.plan.BuildPlan;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Executes the current plan locally: no control plane, no Docker, no other services. Independent
 * tasks run concurrently, a task starts only once its selected dependencies have succeeded, and a
 * failure stops everything downstream of it.
 */
@Command(name = "run", description = "Run the tasks the current changes affect.")
final class RunCommand implements Callable<Integer> {

    /** How long a Ctrl-C waits for tasks to be torn down before the JVM stops caring. */
    private static final Duration CANCELLATION_GRACE = Duration.ofSeconds(10);

    @Mixin private SelectionOptions selection;

    @Option(
            names = {"-j", "--jobs"},
            paramLabel = "<count>",
            description = "Maximum tasks to run at once (default: one per available processor).")
    private Integer jobs;

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        ProjectWorkspace workspace = ProjectWorkspace.load();
        BuildPlan plan = selection.resolvePlan(workspace);
        PrintWriter out = spec.commandLine().getOut();

        out.println("ForgeCI run");
        out.println();
        if (plan.isEmpty()) {
            out.println("Nothing to run — no task is affected by the current changes.");
            out.flush();
            return ExitCode.SUCCESS;
        }

        int concurrency = resolveConcurrency(plan);
        out.printf(
                "Running %d task%s, up to %d at a time%n",
                plan.selected().size(), plan.selected().size() == 1 ? "" : "s", concurrency);
        out.println();
        out.flush();

        CacheCoordinator coordinator = new CacheCoordinator(workspace);
        LocalExecutor executor =
                new LocalExecutor(
                        new CachingTaskRunner(
                                new ProcessTaskRunner(workspace.directory()), coordinator),
                        concurrency,
                        defaultTimeout(workspace));
        ExecutionReport report =
                cancelableExecute(
                        () ->
                                executor.execute(
                                        workspace.graph(),
                                        plan.selectedTaskNames(),
                                        new StreamingRunListener(out)));

        printSummary(out, report);
        return report.succeeded() ? ExitCode.SUCCESS : ExitCode.BUILD_FAILED;
    }

    private int resolveConcurrency(BuildPlan plan) {
        if (jobs != null && jobs < 1) {
            throw new CliException("--jobs must be at least 1, got " + jobs);
        }
        int requested = jobs != null ? jobs : LocalExecutor.defaultConcurrency();
        return Math.max(1, Math.min(requested, plan.selected().size()));
    }

    private Duration defaultTimeout(ProjectWorkspace workspace) {
        String configured = workspace.config().defaults().timeout();
        return configured == null ? LocalExecutor.DEFAULT_TIMEOUT : Durations.parse(configured);
    }

    /**
     * Ctrl-C reaches a JVM as shutdown, not as an exception, so the hook interrupts the run thread
     * — that is the signal {@code LocalExecutor} turns into killing task process trees. The hook
     * waits on a latch rather than joining the run thread: during shutdown that thread blocks
     * forever in {@code System.exit}, so a join could only ever time out.
     */
    private static ExecutionReport cancelableExecute(Supplier<ExecutionReport> body) {
        Thread runThread = Thread.currentThread();
        CountDownLatch finished = new CountDownLatch(1);
        Thread hook =
                new Thread(
                        () -> {
                            runThread.interrupt();
                            try {
                                finished.await(CANCELLATION_GRACE.toSeconds(), TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "forge-cancel");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return body.get();
        } finally {
            finished.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // shutdown already in progress; the hook is doing its job
            }
        }
    }

    private static void printSummary(PrintWriter out, ExecutionReport report) {
        out.println();
        out.println("Result");
        for (TaskOutcome outcome : report.outcomes()) {
            String elapsed =
                    outcome.duration().isZero() ? "" : Durations.format(outcome.duration());
            String row =
                    String.format(
                            "  %-24s %-10s %8s  %s",
                            outcome.task(), outcome.status(), elapsed, outcome.detail());
            out.println(row.stripTrailing());
        }
        out.println();
        out.printf(
                "Run: %d succeeded, %d failed, %d skipped in %s%n",
                report.count(TaskStatus.SUCCEEDED),
                report.count(TaskStatus.FAILED) + report.count(TaskStatus.TIMED_OUT),
                report.count(TaskStatus.SKIPPED) + report.count(TaskStatus.CANCELED),
                Durations.format(report.wallClock()));
        out.flush();
    }
}
