package dev.forgeci.controlplane.service;

/**
 * An upload's declared metadata didn't match its bytes, or a stored object no longer matches its
 * recorded digest/size.
 */
public class ArtifactIntegrityException extends RuntimeException {

    public ArtifactIntegrityException(String message) {
        super(message);
    }
}
