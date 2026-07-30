package dev.forgeci.cache;

/** What a cache key resolved to the last time it was successfully stored: an artifact and its size. */
record CacheManifest(String digest, long size) {}
