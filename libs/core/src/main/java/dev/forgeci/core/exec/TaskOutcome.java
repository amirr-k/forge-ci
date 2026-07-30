package dev.forgeci.core.exec;

import java.time.Duration;

/**
 * What happened to one task in one run.
 *
 * @param task the task name
 * @param status terminal status
 * @param exitCode the process exit code, or {@code null} when no process ran
 * @param duration wall-clock time the task itself took
 * @param detail short operator-facing explanation, empty when the status says everything
 */
public record TaskOutcome(String task, TaskStatus status, Integer exitCode, Duration duration, String detail) {

    public static TaskOutcome succeeded(String task, Duration duration) {
        return new TaskOutcome(task, TaskStatus.SUCCEEDED, 0, duration, "");
    }

    public static TaskOutcome failed(String task, int exitCode, Duration duration) {
        return new TaskOutcome(task, TaskStatus.FAILED, exitCode, duration, "exit code " + exitCode);
    }

    public static TaskOutcome failedToStart(String task, Duration duration, String detail) {
        return new TaskOutcome(task, TaskStatus.FAILED, null, duration, detail);
    }

    public static TaskOutcome timedOut(String task, Duration timeout) {
        return new TaskOutcome(
                task, TaskStatus.TIMED_OUT, null, timeout, "timed out after " + Durations.format(timeout));
    }

    public static TaskOutcome skipped(String task, String detail) {
        return new TaskOutcome(task, TaskStatus.SKIPPED, null, Duration.ZERO, detail);
    }

    public static TaskOutcome canceled(String task, Duration duration) {
        return new TaskOutcome(task, TaskStatus.CANCELED, null, duration, "canceled");
    }
}
