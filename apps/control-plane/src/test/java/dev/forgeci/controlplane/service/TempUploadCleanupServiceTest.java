package dev.forgeci.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.controlplane.config.S3Properties;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

class TempUploadCleanupServiceTest extends ControlPlaneIntegrationTest {

    @Autowired private TempUploadCleanupService cleanup;
    @Autowired private S3Client s3;
    @Autowired private S3Properties props;

    @Test
    void removesTempObjectsOlderThanTheTtlButLeavesFreshOnesAlone() {
        String abandonedKey = props.getTempPrefix() + UUID.randomUUID();
        String freshKey = props.getTempPrefix() + UUID.randomUUID();
        s3.putObject(b -> b.bucket(props.getBucket()).key(abandonedKey), RequestBody.fromBytes(new byte[] {1}));
        s3.putObject(b -> b.bucket(props.getBucket()).key(freshKey), RequestBody.fromBytes(new byte[] {2}));

        // sweep as if run well after the TTL — both objects were created "now," so this proves the
        // TTL check works without needing to actually wait an hour in a test
        int deleted = cleanup.sweep(Instant.now().plus(props.getTempTtl()).plus(Duration.ofMinutes(1)));

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThatObjectIsGone(abandonedKey);
        assertThatObjectIsGone(freshKey);
    }

    @Test
    void aSweepRightAfterUploadLeavesObjectsAlone() {
        String key = props.getTempPrefix() + UUID.randomUUID();
        s3.putObject(b -> b.bucket(props.getBucket()).key(key), RequestBody.fromBytes(new byte[] {3}));

        cleanup.sweep(Instant.now());

        var listing =
                s3.listObjectsV2(ListObjectsV2Request.builder().bucket(props.getBucket()).prefix(key).build());
        assertThat(listing.contents()).anyMatch(o -> o.key().equals(key));
    }

    private void assertThatObjectIsGone(String key) {
        try {
            s3.headObject(b -> b.bucket(props.getBucket()).key(key));
            org.junit.jupiter.api.Assertions.fail("expected " + key + " to have been swept");
        } catch (NoSuchKeyException expected) {
            // swept, as expected
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
        }
    }
}
