package dev.forgeci.controlplane.domain;

import java.util.Set;

/**
 * Task-run lifecycle. Terminal states ({@link #SUCCEEDED}, {@link #FAILED}, {@link #CACHED},
 * {@link #SKIPPED}) never transition further.
 */
public enum TaskRunState {
    PENDING,
    READY,
    LEASED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    RETRY_WAIT,
    CACHED,
    SKIPPED;

    private static final Set<TaskRunState> TERMINAL = Set.of(SUCCEEDED, FAILED, CACHED, SKIPPED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
