package dev.forgeci.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A content-addressed build output. Uploaded by workers starting in phase 4/5. */
@Entity
@Table(name = "artifacts")
public class Artifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String digest;

    @Column(name = "object_store_key", nullable = false)
    private String objectStoreKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "checksum_algorithm", nullable = false)
    private String checksumAlgorithm;

    @Column(name = "manifest_version", nullable = false)
    private int manifestVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "producer_cache_key", nullable = false)
    private String producerCacheKey;

    protected Artifact() {}

    public Artifact(
            String digest,
            String objectStoreKey,
            long sizeBytes,
            String checksumAlgorithm,
            int manifestVersion,
            String producerCacheKey) {
        this.digest = digest;
        this.objectStoreKey = objectStoreKey;
        this.sizeBytes = sizeBytes;
        this.checksumAlgorithm = checksumAlgorithm;
        this.manifestVersion = manifestVersion;
        this.producerCacheKey = producerCacheKey;
    }

    public Long getId() {
        return id;
    }

    public String getDigest() {
        return digest;
    }

    public String getObjectStoreKey() {
        return objectStoreKey;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksumAlgorithm() {
        return checksumAlgorithm;
    }

    public int getManifestVersion() {
        return manifestVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getProducerCacheKey() {
        return producerCacheKey;
    }
}
