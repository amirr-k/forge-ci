package dev.forgeci.protocol;

public record WorkerRegistrationResponse(long workerId, long heartbeatIntervalMs) {}
