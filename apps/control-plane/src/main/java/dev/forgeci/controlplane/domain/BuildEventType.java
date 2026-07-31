package dev.forgeci.controlplane.domain;

/** One entry per accepted state transition — never emitted for a rejected transition. */
public enum BuildEventType {
    BUILD_CREATED,
    BUILD_PLANNING,
    BUILD_RUNNING,
    BUILD_SUCCEEDED,
    BUILD_FAILED,
    BUILD_CANCELED,
    TASK_RUN_CREATED,
    TASK_RUN_READY,
    TASK_RUN_LEASED,
    TASK_RUN_RUNNING,
    TASK_RUN_SUCCEEDED,
    TASK_RUN_FAILED,
    TASK_RUN_RETRY_WAIT,
    TASK_RUN_CACHED,
    TASK_RUN_SKIPPED
}
