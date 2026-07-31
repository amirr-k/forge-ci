package dev.forgeci.controlplane.kafka;

/**
 * {@code forge.task-results} message schema, version 1 — the same five identifiers every worker
 * report carries per the fixed worker protocol (build id is implied by task run id; not repeated
 * here since the consumer looks the task run up before touching anything).
 */
public record TaskResultEvent(
        int schemaVersion,
        long taskRunId,
        long workerId,
        String leaseToken,
        int attemptId,
        boolean success,
        Integer exitCode,
        String failureReason,
        String artifactDigest) {

    public TaskResultEvent(
            long taskRunId, long workerId, String leaseToken, int attemptId, boolean success, Integer exitCode, String failureReason, String artifactDigest) {
        this(1, taskRunId, workerId, leaseToken, attemptId, success, exitCode, failureReason, artifactDigest);
    }
}
