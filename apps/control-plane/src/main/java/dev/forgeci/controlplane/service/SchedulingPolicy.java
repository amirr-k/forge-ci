package dev.forgeci.controlplane.service;

import java.util.Locale;

/**
 * Which ready task the scheduler releases first. Selected by {@code forge.scheduler.policy}.
 *
 * <p>All three see the same eligible set — a task is claimable when its dependencies are satisfied
 * and its build is still running — so the choice affects makespan, never correctness.
 */
public enum SchedulingPolicy {

    /** Oldest ready task first. The baseline the other two are measured against. */
    FIFO,

    /** Longest remaining chain in hops first. */
    CRITICAL_PATH,

    /** Longest remaining chain in estimated milliseconds first, from observed task history. */
    CRITICAL_PATH_DURATION;

    /** Accepts the kebab-case spelling used in configuration, e.g. {@code critical-path}. */
    public static SchedulingPolicy from(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (SchedulingPolicy policy : values()) {
            if (policy.name().equals(normalized)) {
                return policy;
            }
        }
        throw new IllegalArgumentException(
                "unknown forge.scheduler.policy '"
                        + value
                        + "' — expected one of fifo, critical-path, critical-path-duration");
    }
}
