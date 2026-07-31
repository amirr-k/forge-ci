package dev.forgeci.protocol;

public record TaskResultReportRequest(
        long workerId,
        String leaseToken,
        int attemptId,
        boolean success,
        Integer exitCode,
        String failureReason,
        String artifactDigest) {}
