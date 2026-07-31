package dev.forgeci.controlplane.service;

/** A transition was rejected because it is not legal from the entity's current state. */
public class InvalidTransitionException extends RuntimeException {

    public InvalidTransitionException(String message) {
        super(message);
    }
}
