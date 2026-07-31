package dev.forgeci.controlplane.service;

import dev.forgeci.cache.Digests;
import dev.forgeci.controlplane.config.S3Properties;
import dev.forgeci.controlplane.domain.Artifact;
import dev.forgeci.controlplane.domain.CacheEntry;
import dev.forgeci.controlplane.domain.Project;
import dev.forgeci.controlplane.repository.ArtifactRepository;
import dev.forgeci.controlplane.repository.CacheEntryRepository;
import dev.forgeci.controlplane.repository.ProjectRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The content-addressed artifact protocol from architecture.md#content-addressed-artifacts,
 * against S3: temp upload, control-plane metadata verification, commit to the final digest key,
 * transactional manifest association, temp cleanup. This becomes the permanent protocol phase 5's
 * workers reuse unchanged, so the S3 key layout ({@link S3Properties#objectKey}) is fixed here.
 *
 * <p>A cache hit is never reported merely because a manifest row or an object-store key exists —
 * {@link #download} always re-verifies the fetched bytes against the digest and size the manifest
 * recorded before returning them.
 */
@Service
public class RemoteArtifactService {

    private final S3Client s3;
    private final S3Properties props;
    private final ArtifactRepository artifactRepository;
    private final CacheEntryRepository cacheEntryRepository;
    private final ProjectRepository projectRepository;

    public RemoteArtifactService(
            S3Client s3,
            S3Properties props,
            ArtifactRepository artifactRepository,
            CacheEntryRepository cacheEntryRepository,
            ProjectRepository projectRepository) {
        this.s3 = s3;
        this.props = props;
        this.artifactRepository = artifactRepository;
        this.cacheEntryRepository = cacheEntryRepository;
        this.projectRepository = projectRepository;
    }

    public record DownloadResult(String digest, byte[] content) {}

    /**
     * Uploads to a temporary key, verifies the declared digest/size against bytes the control
     * plane recomputes itself (never trusting the caller's claim alone), commits to the final
     * digest key, and transactionally associates the cache key with it. Any failure along the way
     * — a metadata mismatch, an S3 error — leaves no artifact row and no cache-entry row behind: a
     * partial or rejected upload is never exposed as a hit.
     */
    @Transactional
    public Artifact commit(long projectId, String cacheKey, String declaredDigest, long declaredSize, byte[] content) {
        Project project =
                projectRepository.findById(projectId).orElseThrow(() -> new NotFoundException("project " + projectId + " not found"));

        if (content.length != declaredSize) {
            throw new ArtifactIntegrityException(
                    "upload for " + cacheKey + " declared size " + declaredSize + " but sent " + content.length + " bytes");
        }
        String actualDigest = Digests.sha256(content);
        if (!actualDigest.equals(declaredDigest)) {
            throw new ArtifactIntegrityException(
                    "upload for " + cacheKey + " declared digest " + declaredDigest + " but bytes hash to " + actualDigest);
        }

        String tempKey = props.getTempPrefix() + UUID.randomUUID();
        s3.putObject(PutObjectRequest.builder().bucket(props.getBucket()).key(tempKey).build(), RequestBody.fromBytes(content));
        HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(props.getBucket()).key(tempKey).build());
        if (head.contentLength() != declaredSize) {
            s3.deleteObject(b -> b.bucket(props.getBucket()).key(tempKey));
            throw new ArtifactIntegrityException("uploaded object for " + cacheKey + " has size " + head.contentLength() + " on the wire, expected " + declaredSize);
        }

        Artifact artifact =
                artifactRepository
                        .findByDigest(actualDigest)
                        .orElseGet(() -> commitToFinalKey(tempKey, actualDigest, declaredSize, cacheKey));
        s3.deleteObject(b -> b.bucket(props.getBucket()).key(tempKey));

        CacheEntry entry = cacheEntryRepository.findByCacheKey(cacheKey).orElse(null);
        if (entry == null) {
            cacheEntryRepository.save(new CacheEntry(cacheKey, artifact, project));
        } else {
            entry.setArtifact(artifact);
            cacheEntryRepository.save(entry);
        }
        return artifact;
    }

    private Artifact commitToFinalKey(String tempKey, String digest, long size, String cacheKey) {
        String finalKey = props.objectKey(digest);
        s3.copyObject(
                CopyObjectRequest.builder()
                        .sourceBucket(props.getBucket())
                        .sourceKey(tempKey)
                        .destinationBucket(props.getBucket())
                        .destinationKey(finalKey)
                        .build());
        return artifactRepository.saveAndFlush(new Artifact(digest, finalKey, size, "SHA-256", 1, cacheKey));
    }

    /** Resolves a cache key to its manifest and re-verifies the object it points at before returning it. */
    @Transactional(readOnly = true)
    public DownloadResult download(String cacheKey, long projectId) {
        CacheEntry entry =
                cacheEntryRepository
                        .findByCacheKeyAndProjectId(cacheKey, projectId)
                        .orElseThrow(() -> new NotFoundException("no cache entry for key " + cacheKey));
        return fetchAndVerify(entry.getArtifact());
    }

    private DownloadResult fetchAndVerify(Artifact artifact) {
        byte[] content;
        try {
            content = s3.getObjectAsBytes(b -> b.bucket(props.getBucket()).key(artifact.getObjectStoreKey())).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ArtifactIntegrityException("artifact " + artifact.getDigest() + " is missing from object storage");
        }
        if (content.length != artifact.getSizeBytes()) {
            throw new ArtifactIntegrityException(
                    "stored object for " + artifact.getDigest() + " has size " + content.length + ", expected " + artifact.getSizeBytes());
        }
        String actualDigest = Digests.sha256(content);
        if (!actualDigest.equals(artifact.getDigest())) {
            throw new ArtifactIntegrityException(
                    "stored object at " + artifact.getObjectStoreKey() + " does not match its digest " + artifact.getDigest() + " (got " + actualDigest + ") — object storage corruption");
        }
        return new DownloadResult(artifact.getDigest(), content);
    }
}
