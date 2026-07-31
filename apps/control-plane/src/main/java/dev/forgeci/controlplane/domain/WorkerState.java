package dev.forgeci.controlplane.domain;

/** {@code ACTIVE} accepts claims; {@code UNHEALTHY} is excluded until a fresh heartbeat arrives. */
public enum WorkerState {
    ACTIVE,
    UNHEALTHY
}
