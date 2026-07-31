package dev.forgeci.cache;

import java.util.Optional;

/**
 * Client-side half of the remote artifact protocol: register the project once to get a stable id,
 * look an already-computed cache key up against the shared store, and upload a freshly stored
 * archive so another workspace pointed at the same control plane can reuse it. Local mode from
 * phase 1/2 never constructs one of these — {@link TaskCache}'s single-argument constructor keeps
 * working with zero infrastructure regardless of whether this interface has an implementation.
 */
public interface RemoteArtifactClient {

    /** Idempotent: registering the same project name twice returns the same id. */
    long ensureProject(
            String name, String repositoryIdentity, String defaultBranch, int configVersion);

    /**
     * The archive bytes for {@code cacheKey}, already verified against the digest and size the
     * remote store recorded — or empty if the remote store has no entry for this key. Never returns
     * bytes that failed verification; a caller sees either a trustworthy hit or a miss.
     */
    Optional<byte[]> lookup(long projectId, String cacheKey);

    /**
     * Uploads {@code archive} as the artifact for {@code cacheKey}. Best-effort: local mode must
     * not depend on this succeeding.
     */
    void upload(long projectId, String cacheKey, byte[] archive);
}
