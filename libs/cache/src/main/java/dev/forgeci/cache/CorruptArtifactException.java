package dev.forgeci.cache;

/** A stored artifact's bytes do not match its recorded digest or size — never treat it as a hit. */
public final class CorruptArtifactException extends CacheException {

    public CorruptArtifactException(String message) {
        super(message);
    }
}
