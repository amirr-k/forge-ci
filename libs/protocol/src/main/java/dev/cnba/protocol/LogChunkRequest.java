package dev.cnba.protocol;

import java.util.List;

public record LogChunkRequest(
        long workerId, String leaseToken, int attemptId, List<String> lines) {}
