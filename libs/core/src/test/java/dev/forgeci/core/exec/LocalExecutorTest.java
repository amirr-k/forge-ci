package dev.forgeci.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forgeci.core.graph.GraphFixtures;
import dev.forgeci.core.graph.TaskGraph;
import dev.forgeci.core.model.TaskDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalExecutorTest {

    private static final Duration TASK_DURATION = Duration.ofMillis(600);

    private final TaskGraph graph = TaskGraph.build(GraphFixtures.demoConfig());

    @Test
    void runsIndependentTasksConcurrently() {
        FakeRunner runner = new FakeRunner(TASK_DURATION, Map.of());
        LocalExecutor executor = new LocalExecutor(runner, 2, Duration.ofMinutes(1));

        ExecutionReport report =
                executor.execute(
                        graph, List.of("catalog:build", "accounts:test"), ExecutionListener.NONE);

        assertTrue(report.succeeded());
        // two 600ms tasks with two slots: overlapping means ~600ms, serial would be ~1200ms
        assertTrue(
                report.wallClock().toMillis() < TASK_DURATION.multipliedBy(2).toMillis() - 100,
                "expected concurrent execution, took " + report.wallClock().toMillis() + "ms");
        assertEquals(2, runner.peakInFlight());
    }

    @Test
    void neverExceedsTheConcurrencyLimit() {
        FakeRunner runner = new FakeRunner(Duration.ofMillis(200), Map.of());
        LocalExecutor executor = new LocalExecutor(runner, 2, Duration.ofMinutes(1));

        executor.execute(
                graph,
                List.of("catalog:build", "accounts:test", "storefront:build", "pricing:test"),
                ExecutionListener.NONE);

        assertEquals(2, runner.peakInFlight());
    }

    @Test
    void startsATaskOnlyAfterItsDependencies() {
        FakeRunner runner = new FakeRunner(Duration.ofMillis(50), Map.of());
        LocalExecutor executor = new LocalExecutor(runner, 4, Duration.ofMinutes(1));

        executor.execute(
                graph,
                List.of("pricing:test", "pricing:build", "checkout:integration"),
                ExecutionListener.NONE);

        List<String> order = runner.completionOrder();
        assertTrue(
                order.indexOf("pricing:test") < order.indexOf("pricing:build")
                        && order.indexOf("pricing:build") < order.indexOf("checkout:integration"),
                "unexpected order: " + order);
    }

    @Test
    void skipsEverythingDownstreamOfAFailure() {
        FakeRunner runner = new FakeRunner(Duration.ofMillis(20), Map.of("pricing:test", 1));
        LocalExecutor executor = new LocalExecutor(runner, 4, Duration.ofMinutes(1));

        ExecutionReport report =
                executor.execute(
                        graph,
                        List.of(
                                "catalog:build",
                                "pricing:test",
                                "pricing:build",
                                "checkout:integration"),
                        ExecutionListener.NONE);

        Map<String, TaskOutcome> outcomes = byTask(report);
        assertEquals(TaskStatus.FAILED, outcomes.get("pricing:test").status());
        assertEquals(TaskStatus.SKIPPED, outcomes.get("pricing:build").status());
        assertEquals(TaskStatus.SKIPPED, outcomes.get("checkout:integration").status());
        assertEquals("dependency pricing:test failed", outcomes.get("pricing:build").detail());
        assertEquals(
                "dependency pricing:build was skipped",
                outcomes.get("checkout:integration").detail());
        // an independent task is unaffected by the failure
        assertEquals(TaskStatus.SUCCEEDED, outcomes.get("catalog:build").status());
        assertTrue(
                !runner.completionOrder().contains("pricing:build"), "skipped task must not run");
    }

    @Test
    void treatsUnselectedDependenciesAsAlreadySatisfied() {
        FakeRunner runner = new FakeRunner(Duration.ofMillis(20), Map.of());
        LocalExecutor executor = new LocalExecutor(runner, 2, Duration.ofMinutes(1));

        ExecutionReport report =
                executor.execute(graph, List.of("pricing:build"), ExecutionListener.NONE);

        assertTrue(report.succeeded());
        assertEquals(List.of("pricing:build"), runner.completionOrder());
    }

    @Test
    void reportsOutcomesInPlanOrder() {
        FakeRunner runner = new FakeRunner(Duration.ofMillis(20), Map.of());
        LocalExecutor executor = new LocalExecutor(runner, 4, Duration.ofMinutes(1));

        ExecutionReport report =
                executor.execute(
                        graph,
                        List.of("pricing:test", "pricing:build", "catalog:build"),
                        ExecutionListener.NONE);

        assertEquals(
                List.of("pricing:test", "pricing:build", "catalog:build"),
                report.outcomes().stream().map(TaskOutcome::task).toList());
    }

    @Test
    void passesTheTaskTimeoutToTheRunner() {
        FakeRunner runner = new FakeRunner(Duration.ofMillis(10), Map.of());
        LocalExecutor executor = new LocalExecutor(runner, 1, Duration.ofSeconds(7));

        executor.execute(graph, List.of("catalog:build"), ExecutionListener.NONE);

        // the fixture declares timeout: 10m, which must win over the executor default
        assertEquals(Duration.ofMinutes(10), runner.timeoutFor("catalog:build"));
    }

    private static Map<String, TaskOutcome> byTask(ExecutionReport report) {
        Map<String, TaskOutcome> outcomes = new java.util.LinkedHashMap<>();
        for (TaskOutcome outcome : report.outcomes()) {
            outcomes.put(outcome.task(), outcome);
        }
        return outcomes;
    }

    /**
     * Sleeps for a fixed duration instead of starting a process, and records what it was asked to
     * do.
     */
    private static final class FakeRunner implements TaskRunner {

        private final Duration duration;
        private final Map<String, Integer> failures;
        private final List<String> completed = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Duration> timeouts =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peakInFlight = new AtomicInteger();

        FakeRunner(Duration duration, Map<String, Integer> failures) {
            this.duration = duration;
            this.failures = failures;
        }

        @Override
        public TaskOutcome run(TaskDefinition task, Duration timeout, ExecutionListener listener) {
            timeouts.put(task.name(), timeout);
            peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TaskOutcome.canceled(task.name(), Duration.ZERO);
            } finally {
                inFlight.decrementAndGet();
            }
            completed.add(task.name());
            Integer exitCode = failures.get(task.name());
            return exitCode == null
                    ? TaskOutcome.succeeded(task.name(), duration)
                    : TaskOutcome.failed(task.name(), exitCode, duration);
        }

        List<String> completionOrder() {
            return List.copyOf(completed);
        }

        int peakInFlight() {
            return peakInFlight.get();
        }

        Duration timeoutFor(String task) {
            return timeouts.get(task);
        }
    }
}
