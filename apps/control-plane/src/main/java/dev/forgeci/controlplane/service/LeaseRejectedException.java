package dev.forgeci.controlplane.service;

/**
 * A worker report (log or result) carried a stale or mismatched lease token, worker id, or attempt
 * id.
 */
public class LeaseRejectedException extends RuntimeException {
    public LeaseRejectedException(String message) {
        super(message);
    }
}
