package dev.cnba.protocol;

public record WorkerRegistrationResponse(long workerId, long heartbeatIntervalMs) {}
