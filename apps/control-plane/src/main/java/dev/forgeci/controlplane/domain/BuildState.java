package dev.forgeci.controlplane.domain;

import java.util.Set;

/**
 * Build lifecycle. Terminal states ({@link #SUCCEEDED}, {@link #FAILED}, {@link #CANCELED}) never
 * transition further.
 */
public enum BuildState {
    CREATED,
    PLANNING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED;

    private static final Set<BuildState> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
