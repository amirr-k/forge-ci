package dev.forgeci.controlplane.api.dto;

import dev.forgeci.controlplane.domain.Artifact;
import java.time.Instant;

public record ArtifactResponse(
        String digest,
        String objectStoreKey,
        long sizeBytes,
        String checksumAlgorithm,
        int manifestVersion,
        Instant createdAt) {

    public static ArtifactResponse from(Artifact artifact) {
        return new ArtifactResponse(
                artifact.getDigest(),
                artifact.getObjectStoreKey(),
                artifact.getSizeBytes(),
                artifact.getChecksumAlgorithm(),
                artifact.getManifestVersion(),
                artifact.getCreatedAt());
    }
}
