package dev.forgeci.cache;

/** An archive entry's path would extract outside the requested output root. */
public final class PathTraversalException extends CacheException {

    public PathTraversalException(String message) {
        super(message);
    }
}
