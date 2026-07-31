package dev.forgeci.controlplane.demo;

/** Thrown when another guest demo build is already in flight, or a guest is rate-limited. */
public class DemoBusyException extends RuntimeException {

    public DemoBusyException() {
        super("the public demo can only run one build at a time — please try again shortly");
    }

    public DemoBusyException(String message) {
        super(message);
    }
}
