package dev.forgeci.protocol;

/**
 * {@code shouldCrash} carries a pending crash-injection request — see
 * architecture.md#worker-protocol.
 */
public record HeartbeatResponse(boolean shouldCrash) {}
