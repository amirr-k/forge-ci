package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.config.S3Properties;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * A temp upload is deleted once its commit succeeds ({@link RemoteArtifactService#commit}), but a
 * client that dies or a request that never reaches the control plane mid-upload can leave one
 * behind. This sweep reclaims anything under the temp prefix older than the configured TTL —
 * nothing here is ever a candidate for a cache hit, since a manifest only ever points at a final
 * digest key, never a temp one.
 */
@Service
public class TempUploadCleanupService {

    private static final Logger log = LoggerFactory.getLogger(TempUploadCleanupService.class);

    private final S3Client s3;
    private final S3Properties props;
    private final Clock clock;

    public TempUploadCleanupService(S3Client s3, S3Properties props, Clock clock) {
        this.s3 = s3;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${forge.artifacts.cleanup-interval-ms:900000}")
    public void sweepScheduled() {
        int deleted = sweep(Instant.now(clock));
        if (deleted > 0) {
            log.info("removed {} abandoned temp upload(s)", deleted);
        }
    }

    /** Deletes every object under the temp prefix last modified before {@code now - ttl}. Returns how many it removed. */
    public int sweep(Instant now) {
        Instant cutoff = now.minus(props.getTempTtl());
        int deleted = 0;
        ListObjectsV2Iterable pages =
                s3.listObjectsV2Paginator(
                        ListObjectsV2Request.builder().bucket(props.getBucket()).prefix(props.getTempPrefix()).build());
        for (S3Object object : pages.contents()) {
            if (object.lastModified().isBefore(cutoff)) {
                s3.deleteObject(b -> b.bucket(props.getBucket()).key(object.key()));
                deleted++;
            }
        }
        return deleted;
    }
}
