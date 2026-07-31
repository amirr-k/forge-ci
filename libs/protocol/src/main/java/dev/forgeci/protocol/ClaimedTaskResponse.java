package dev.forgeci.protocol;

import java.util.List;

/**
 * Everything a worker needs to execute one task run and report back, per the worker protocol
 * fixed in phase 5: every report a worker makes must carry {@code buildId}, {@code taskRunId},
 * {@code attemptId}, {@code workerId}, and {@code leaseToken} — this response is where the worker
 * first learns all five.
 */
public record ClaimedTaskResponse(
        long taskRunId,
        long buildId,
        long projectId,
        String taskName,
        String cacheKey,
        List<String> command,
        List<String> outputs,
        List<String> environment,
        int timeoutSeconds,
        int attemptId,
        long workerId,
        String leaseToken) {}
