package dev.forgeci.cli;

import dev.forgeci.cache.TaskCache;
import dev.forgeci.core.exec.ExecutionListener;
import dev.forgeci.core.exec.TaskOutcome;
import dev.forgeci.core.exec.TaskRunner;
import dev.forgeci.core.exec.TaskStatus;
import dev.forgeci.core.model.TaskDefinition;
import java.time.Duration;
import java.util.List;

/**
 * Wraps a {@link TaskRunner}, restoring a verified cache hit instead of running the command, and
 * storing a cacheable task's declared outputs after it succeeds. A caching failure — a full disk, a
 * permissions error — never fails an otherwise-successful task; it just means that result is not
 * reused next time.
 */
final class CachingTaskRunner implements TaskRunner {

    private final TaskRunner delegate;
    private final CacheCoordinator coordinator;

    CachingTaskRunner(TaskRunner delegate, CacheCoordinator coordinator) {
        this.delegate = delegate;
        this.coordinator = coordinator;
    }

    @Override
    public TaskOutcome run(TaskDefinition task, Duration timeout, ExecutionListener listener) {
        CacheCoordinator.Decision decision = coordinator.decide(task.name());
        if (decision.hit()) {
            listener.taskStarted(task.name(), List.of("cache hit — restoring outputs"));
            coordinator.restore(decision.cacheHit());
            TaskOutcome outcome = TaskOutcome.cached(task.name());
            listener.taskFinished(outcome);
            return outcome;
        }

        TaskOutcome outcome = delegate.run(task, timeout, listener);
        if (outcome.status() == TaskStatus.SUCCEEDED) {
            coordinator.recordExecuted(task.name(), storeIfCacheable(task, decision));
        }
        return outcome;
    }

    private TaskCache.CacheHit storeIfCacheable(TaskDefinition task, CacheCoordinator.Decision decision) {
        if (!task.cacheable() || task.outputs().isEmpty()) {
            return null;
        }
        try {
            return coordinator.store(decision.key(), task.outputs());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
