package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.domain.WorkerState;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import dev.forgeci.controlplane.repository.WorkerRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Real counters/timers driven by genuine state transitions — never a hardcoded or sampled value.
 */
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
    private final Counter leaseExpirations;
    private final Counter speculativeAttempts;
    private final Counter resultsSubmitted;
    private final Counter resultsAccepted;
    private final Counter duplicateResultsRejected;
    private final Counter speculativeWins;

    public BuildMetrics(
            MeterRegistry registry,
            TaskRunRepository taskRunRepository,
            WorkerRepository workerRepository) {
        this.buildsStarted = Counter.builder("forge.builds.started").register(registry);
        this.buildsSucceeded =
                Counter.builder("forge.builds.completed")
                        .tag("result", "succeeded")
                        .register(registry);
        this.buildsFailed =
                Counter.builder("forge.builds.completed")
                        .tag("result", "failed")
                        .register(registry);
        this.buildsCanceled =
                Counter.builder("forge.builds.completed")
                        .tag("result", "canceled")
                        .register(registry);
        this.buildDuration = Timer.builder("forge.builds.duration").register(registry);
        this.taskDuration = Timer.builder("forge.tasks.duration").register(registry);
        this.taskAttempts = Counter.builder("forge.tasks.attempts").register(registry);
        this.taskRetries = Counter.builder("forge.tasks.retries").register(registry);
        this.leaseExpirations = Counter.builder("forge.tasks.lease_expirations").register(registry);
        this.speculativeAttempts =
                Counter.builder("forge.tasks.attempts")
                        .tag("kind", "speculative")
                        .register(registry);
        // submitted vs accepted is the pair that shows duplicate execution being tolerated rather
        // than prevented: submitted counts every result a worker sent, accepted counts the ones
        // that won their task run. The difference is exactly the wasted-but-harmless work.
        this.resultsSubmitted = Counter.builder("forge.results.submitted").register(registry);
        this.resultsAccepted = Counter.builder("forge.results.accepted").register(registry);
        this.duplicateResultsRejected =
                Counter.builder("forge.results.rejected")
                        .tag("reason", "duplicate")
                        .register(registry);
        this.speculativeWins =
                Counter.builder("forge.results.accepted")
                        .tag("kind", "speculative")
                        .register(registry);
        registry.gauge(
                "forge.scheduler.ready_queue_depth",
                taskRunRepository,
                repo -> repo.countByState(TaskRunState.READY));
        registry.gauge(
                "forge.workers.active",
                workerRepository,
                repo -> repo.countByState(WorkerState.ACTIVE));
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

    public void leaseExpired() {
        leaseExpirations.increment();
    }

    public void taskCompleted(Duration duration) {
        taskDuration.record(duration);
    }

    public void speculativeAttemptStarted() {
        speculativeAttempts.increment();
    }

    public void resultSubmitted() {
        resultsSubmitted.increment();
    }

    public void resultAccepted(boolean speculative) {
        resultsAccepted.increment();
        if (speculative) {
            speculativeWins.increment();
        }
    }

    public void duplicateResultRejected() {
        duplicateResultsRejected.increment();
    }
}
