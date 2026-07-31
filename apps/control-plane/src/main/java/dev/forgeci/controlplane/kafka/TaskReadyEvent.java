package dev.forgeci.controlplane.kafka;

/** {@code forge.task-ready} message schema, version 1. Durable notification only — MySQL's task_runs.state remains authoritative. */
public record TaskReadyEvent(int schemaVersion, long buildId, long taskRunId, String taskName, String cacheKey) {

    public TaskReadyEvent(long buildId, long taskRunId, String taskName, String cacheKey) {
        this(1, buildId, taskRunId, taskName, cacheKey);
    }
}
