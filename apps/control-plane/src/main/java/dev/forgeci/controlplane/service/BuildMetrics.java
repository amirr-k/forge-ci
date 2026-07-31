package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Real counters/timers driven by genuine state transitions — never a hardcoded or sampled value. */
@Component
public class BuildMetrics {

    private final Counter buildsStarted;
    private final Counter buildsSucceeded;
    private final Counter buildsFailed;
    private final Counter buildsCanceled;
    private final Timer buildDuration;
    private final Timer taskDuration;
    private final Counter taskAttempts;
    private final Counter taskRetries;

    public BuildMetrics(MeterRegistry registry, TaskRunRepository taskRunRepository) {
        this.buildsStarted = Counter.builder("forge.builds.started").register(registry);
        this.buildsSucceeded = Counter.builder("forge.builds.completed").tag("result", "succeeded").register(registry);
        this.buildsFailed = Counter.builder("forge.builds.completed").tag("result", "failed").register(registry);
        this.buildsCanceled = Counter.builder("forge.builds.completed").tag("result", "canceled").register(registry);
        this.buildDuration = Timer.builder("forge.builds.duration").register(registry);
        this.taskDuration = Timer.builder("forge.tasks.duration").register(registry);
        this.taskAttempts = Counter.builder("forge.tasks.attempts").register(registry);
        this.taskRetries = Counter.builder("forge.tasks.retries").register(registry);
        registry.gauge("forge.scheduler.ready_queue_depth", taskRunRepository, repo -> repo.countByState(TaskRunState.READY));
    }

    public void buildStarted() {
        buildsStarted.increment();
    }

    public void buildSucceeded(Duration duration) {
        buildsSucceeded.increment();
        buildDuration.record(duration);
    }

    public void buildFailed(Duration duration) {
        buildsFailed.increment();
        buildDuration.record(duration);
    }

    public void buildCanceled(Duration duration) {
        buildsCanceled.increment();
        buildDuration.record(duration);
    }

    public void taskAttemptStarted() {
        taskAttempts.increment();
    }

    public void taskRetried() {
        taskRetries.increment();
    }

    public void taskCompleted(Duration duration) {
        taskDuration.record(duration);
    }
}
