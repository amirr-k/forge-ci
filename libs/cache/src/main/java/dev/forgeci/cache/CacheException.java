package dev.forgeci.cache;

/** Base type for a cache operation that cannot be trusted to proceed as a hit. */
public class CacheException extends RuntimeException {

    public CacheException(String message) {
        super(message);
    }
}
