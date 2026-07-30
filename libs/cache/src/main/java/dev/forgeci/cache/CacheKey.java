package dev.forgeci.cache;

import java.util.Map;
import java.util.TreeMap;

/**
 * A task's deterministic cache key, plus the per-contributor breakdown {@code forge explain} and
 * miss-reason diffing need. {@code value} is the field every lookup and storage operation keys on;
 * the rest exists only to explain how it was reached.
 *
 * @param schemaVersion the cache-key algorithm version — bumping it invalidates every prior key
 * @param value sha256 hex digest of every contributor below, in a fixed order
 * @param taskDefinitionDigest sha256 hex over the task's declaration: dependencies, declared
 *     inputs/outputs, command, timeout, cacheable flag, and selected environment values
 * @param sourceInputsDigest sha256 hex over the sorted (path, content digest) pairs in {@code
 *     sourceInputDigests}
 * @param sourceInputDigests project-relative path to sha256 hex content digest, for every file
 *     matching a declared input glob, sorted by path
 * @param dependencyArtifactsDigest sha256 hex over the sorted (task name, artifact digest) pairs in
 *     {@code dependencyDigests}
 * @param dependencyDigests direct dependency task name to its artifact digest, sorted by name
 * @param toolchain human-readable toolchain identity, e.g. {@code "Java 21.0.5"}
 */
public record CacheKey(
        int schemaVersion,
        String value,
        String taskDefinitionDigest,
        String sourceInputsDigest,
        Map<String, String> sourceInputDigests,
        String dependencyArtifactsDigest,
        Map<String, String> dependencyDigests,
        String toolchain) {

    public static final int SCHEMA_VERSION = 1;

    public CacheKey {
        sourceInputDigests = Map.copyOf(new TreeMap<>(sourceInputDigests));
        dependencyDigests = Map.copyOf(new TreeMap<>(dependencyDigests));
    }
}
