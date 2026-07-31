package dev.forgeci.worker;

public class ControlPlaneUnavailableException extends RuntimeException {
    public ControlPlaneUnavailableException(String message) {
        super(message);
    }
}
