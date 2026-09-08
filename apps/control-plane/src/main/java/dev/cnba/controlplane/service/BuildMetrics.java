package dev.cnba.controlplane.service;

import dev.cnba.controlplane.domain.TaskRunState;
import dev.cnba.controlplane.domain.WorkerState;
import dev.cnba.controlplane.repository.TaskRunRepository;
import dev.cnba.controlplane.repository.WorkerRepository;
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
        this.buildsStarted = Counter.builder("cnba.builds.started").register(registry);
        this.buildsSucceeded =
                Counter.builder("cnba.builds.completed")
                        .tag("result", "succeeded")
                        .register(registry);
        this.buildsFailed =
                Counter.builder("cnba.builds.completed")
                        .tag("result", "failed")
                        .register(registry);
        this.buildsCanceled =
                Counter.builder("cnba.builds.completed")
                        .tag("result", "canceled")
                        .register(registry);
        this.buildDuration = Timer.builder("cnba.builds.duration").register(registry);
        this.taskDuration = Timer.builder("cnba.tasks.duration").register(registry);
        // every series sharing a metric name carries the *same* tag key, just a different value
        // (matching the buildsSucceeded/Failed/Canceled pattern above) — Prometheus requires a
        // consistent label schema per metric name, so mixing an untagged series with a tagged one
        // under the same name is invalid and the mismatched series silently never appears in a
        // scrape, rather than erroring loudly.
        this.taskAttempts =
                Counter.builder("cnba.tasks.attempts")
                        .tag("speculative", "false")
                        .register(registry);
        this.taskRetries = Counter.builder("cnba.tasks.retries").register(registry);
        this.leaseExpirations = Counter.builder("cnba.tasks.lease_expirations").register(registry);
        this.speculativeAttempts =
                Counter.builder("cnba.tasks.attempts")
                        .tag("speculative", "true")
                        .register(registry);
        // submitted vs accepted is the pair that shows duplicate execution being tolerated rather
        // than prevented: submitted counts every result a worker sent, accepted counts the ones
        // that won their task run. The difference is exactly the wasted-but-harmless work.
        this.resultsSubmitted = Counter.builder("cnba.results.submitted").register(registry);
        this.resultsAccepted =
                Counter.builder("cnba.results.accepted")
                        .tag("speculative", "false")
                        .register(registry);
        this.duplicateResultsRejected =
                Counter.builder("cnba.results.rejected")
                        .tag("reason", "duplicate")
                        .register(registry);
        this.speculativeWins =
                Counter.builder("cnba.results.accepted")
                        .tag("speculative", "true")
                        .register(registry);
        registry.gauge(
                "cnba.scheduler.ready_queue_depth",
                taskRunRepository,
                repo -> repo.countByState(TaskRunState.READY));
        registry.gauge(
                "cnba.workers.active",
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

    /**
     * Exactly one of the two accepted-results series is incremented — never both, or a speculative
     * win would double-count into the overall accepted total once the two series are (correctly)
     * distinguishable by the {@code speculative} tag.
     */
    public void resultAccepted(boolean speculative) {
        if (speculative) {
            speculativeWins.increment();
        } else {
            resultsAccepted.increment();
        }
    }

    public void duplicateResultRejected() {
        duplicateResultsRejected.increment();
    }
}
