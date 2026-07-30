package dev.forgeci.core.exec;

/** The terminal outcome of a task in one local run. */
public enum TaskStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    /** A dependency did not succeed, so the task was never started. */
    SKIPPED,
    /** The run was canceled before the task could finish. */
    CANCELED;

    public boolean isFailure() {
        return this == FAILED || this == TIMED_OUT;
    }
}
