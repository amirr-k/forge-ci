package dev.forgeci.testsupport;

import java.time.Duration;
import java.time.Instant;

/**
 * Measures crash-to-recovery wall-clock time for failure-recovery tests, so the raw number is
 * always a real timer reading, never an assumed "fast enough". Shared here so phase 11's benchmark
 * harness can reuse the exact same measurement instead of building its own.
 */
public final class RecoveryTimer {

    private final Instant startedAt;

    private RecoveryTimer(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public static RecoveryTimer startingNow() {
        return new RecoveryTimer(Instant.now());
    }

    public Duration elapsed() {
        return Duration.between(startedAt, Instant.now());
    }
}
