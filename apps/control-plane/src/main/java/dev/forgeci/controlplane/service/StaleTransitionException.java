package dev.forgeci.controlplane.service;

/**
 * A transition was rejected because the caller's expected version no longer matches the persisted
 * row — e.g. a late report from an expired lease racing an already-accepted result.
 */
public class StaleTransitionException extends RuntimeException {

    public StaleTransitionException(String message) {
        super(message);
    }
}
