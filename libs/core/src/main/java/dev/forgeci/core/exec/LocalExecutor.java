package dev.forgeci.core.exec;

import dev.forgeci.core.graph.TaskGraph;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs a selected set of tasks locally with bounded concurrency: a task starts as soon as every
 * selected dependency has succeeded, so independent tasks overlap. Dependencies outside the selected
 * set are treated as already satisfied — that is what makes an incremental run incremental.
 *
 * <p>Nothing here talks to Kafka, Redis, MySQL, S3, or a control plane; local mode is standalone.
 */
public final class LocalExecutor {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private final TaskRunner runner;
    private final int concurrency;
    private final Duration defaultTimeout;

    public LocalExecutor(TaskRunner runner, int concurrency, Duration defaultTimeout) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1, got " + concurrency);
        }
        this.runner = runner;
        this.concurrency = concurrency;
        this.defaultTimeout = defaultTimeout;
    }

    public static int defaultConcurrency() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /**
     * @param selected task names in topological order; dependencies of a selected task that are not
     *     themselves selected are treated as satisfied
     */
    public ExecutionReport execute(TaskGraph graph, List<String> selected, ExecutionListener listener) {
        Set<String> selectedTasks = new LinkedHashSet<>(selected);
        Map<String, Integer> blockingDependencies = new HashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        for (String task : selectedTasks) {
            int blocking = (int) graph.dependenciesOf(task).stream().filter(selectedTasks::contains).count();
            blockingDependencies.put(task, blocking);
            if (blocking == 0) {
                ready.add(task);
            }
        }

        Map<String, TaskOutcome> outcomes = new LinkedHashMap<>();
        long startedAt = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, this::newWorkerThread);
        CompletionService<TaskOutcome> completion = new ExecutorCompletionService<>(pool);
        try {
            int outstanding = 0;
            while (!ready.isEmpty()) {
                String task = ready.poll();
                completion.submit(() -> runTask(graph, task, listener));
                outstanding++;
            }
            while (outstanding > 0) {
                TaskOutcome outcome = awaitNext(completion);
                outstanding--;
                outcomes.put(outcome.task(), outcome);
                listener.taskFinished(outcome);

                if (outcome.status() == TaskStatus.SUCCEEDED) {
                    for (String dependent : graph.dependentsOf(outcome.task())) {
                        if (selectedTasks.contains(dependent)
                                && blockingDependencies.merge(dependent, -1, Integer::sum) == 0) {
                            completion.submit(() -> runTask(graph, dependent, listener));
                            outstanding++;
                        }
                    }
                } else {
                    blockDownstream(graph, selectedTasks, outcome, outcomes).forEach(listener::taskFinished);
                }
            }
        } catch (RunCanceledException e) {
            pool.shutdownNow();
            for (String task : selectedTasks) {
                outcomes.putIfAbsent(task, TaskOutcome.canceled(task, Duration.ZERO));
            }
        } finally {
            pool.shutdown();
        }

        List<TaskOutcome> ordered = new ArrayList<>(selectedTasks.size());
        for (String task : selectedTasks) {
            TaskOutcome outcome = outcomes.get(task);
            ordered.add(outcome != null ? outcome : TaskOutcome.canceled(task, Duration.ZERO));
        }
        return new ExecutionReport(ordered, Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private TaskOutcome runTask(TaskGraph graph, String name, ExecutionListener listener) {
        var task = graph.task(name);
        Duration timeout = task.timeout() == null ? defaultTimeout : Durations.parse(task.timeout());
        return runner.run(task, timeout, listener);
    }

    /** Marks every selected task downstream of an unsuccessful task as skipped, transitively. */
    private static List<TaskOutcome> blockDownstream(
            TaskGraph graph, Set<String> selectedTasks, TaskOutcome cause, Map<String, TaskOutcome> outcomes) {
        List<TaskOutcome> blocked = new ArrayList<>();
        Deque<TaskOutcome> queue = new ArrayDeque<>();
        queue.add(cause);
        while (!queue.isEmpty()) {
            TaskOutcome current = queue.poll();
            for (String dependent : graph.dependentsOf(current.task())) {
                if (!selectedTasks.contains(dependent) || outcomes.containsKey(dependent)) {
                    continue;
                }
                TaskOutcome skipped =
                        TaskOutcome.skipped(dependent, "dependency " + current.task() + " " + describe(current));
                outcomes.put(dependent, skipped);
                blocked.add(skipped);
                queue.add(skipped);
            }
        }
        return blocked;
    }

    private static String describe(TaskOutcome outcome) {
        return switch (outcome.status()) {
            case FAILED -> "failed";
            case TIMED_OUT -> "timed out";
            case CANCELED -> "was canceled";
            default -> "was skipped";
        };
    }

    private TaskOutcome awaitNext(CompletionService<TaskOutcome> completion) {
        try {
            Future<TaskOutcome> finished = completion.take();
            return finished.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunCanceledException();
        } catch (ExecutionException e) {
            throw new IllegalStateException("task runner threw instead of reporting an outcome", e.getCause());
        }
    }

    private Thread newWorkerThread(Runnable body) {
        Thread thread = new Thread(body, "forge-task");
        thread.setDaemon(true);
        return thread;
    }

    private static final class RunCanceledException extends RuntimeException {
        private RunCanceledException() {
            super(null, null, false, false);
        }
    }
}
