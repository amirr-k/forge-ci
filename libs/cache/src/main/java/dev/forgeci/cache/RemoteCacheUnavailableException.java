package dev.forgeci.cache;

/**
 * The remote store could not be reached or returned something unexpected — never treated as a hit
 * or a fatal error.
 */
public final class RemoteCacheUnavailableException extends CacheException {

    public RemoteCacheUnavailableException(String message) {
        super(message);
    }
}
