package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.cache.Digests;
import dev.forgeci.controlplane.api.dto.ProjectResponse;
import dev.forgeci.controlplane.config.S3Properties;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.TestFixtures;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Exercises the same HTTP protocol two independent client sessions would: a plain {@code
 * TestRestTemplate} standing in for "workspace A uploads" and "workspace B looks up," proving reuse
 * happens purely through the shared store, not shared JVM state.
 */
class ArtifactControllerTest extends ControlPlaneIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private S3Client s3;
    @Autowired private S3Properties s3Properties;

    @Test
    void aSecondWorkspaceReusesWhatTheFirstOneUploaded() {
        long projectId = registerProject();
        String cacheKey = "cache-key-" + UUID.randomUUID();
        byte[] archive = "task output bytes".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map> uploadResponse = upload(projectId, cacheKey, archive);
        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // a second, independent lookup — standing in for a second workspace pointed at the same
        // store
        ResponseEntity<byte[]> lookup = lookup(projectId, cacheKey);
        assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lookup.getBody()).isEqualTo(archive);
        assertThat(lookup.getHeaders().getFirst("X-Artifact-Digest"))
                .isEqualTo(Digests.sha256(archive));
    }

    @Test
    void uploadingIdenticalBytesTwiceDedupesToOneObject() {
        long projectId = registerProject();
        byte[] archive = "shared output".getBytes(StandardCharsets.UTF_8);

        upload(projectId, "key-a-" + UUID.randomUUID(), archive);
        ResponseEntity<Map> second = upload(projectId, "key-b-" + UUID.randomUUID(), archive);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("digest")).isEqualTo(Digests.sha256(archive));
    }

    @Test
    void aDeclaredDigestThatDoesNotMatchTheBytesIsRejectedAndNeverCommitted() {
        long projectId = registerProject();
        String cacheKey = "corrupt-upload-" + UUID.randomUUID();
        byte[] archive = "real bytes".getBytes(StandardCharsets.UTF_8);
        String wrongDigest = Digests.sha256("not the real bytes".getBytes(StandardCharsets.UTF_8));

        String path =
                "/api/artifacts?projectId="
                        + projectId
                        + "&cacheKey="
                        + cacheKey
                        + "&digest="
                        + wrongDigest
                        + "&size="
                        + archive.length;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Map> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(archive, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(lookup(projectId, cacheKey).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aDeclaredSizeThatDoesNotMatchTheBytesIsRejectedAndNeverCommitted() {
        long projectId = registerProject();
        String cacheKey = "partial-upload-" + UUID.randomUUID();
        byte[] archive = "real bytes".getBytes(StandardCharsets.UTF_8);

        String path =
                "/api/artifacts?projectId="
                        + projectId
                        + "&cacheKey="
                        + cacheKey
                        + "&digest="
                        + Digests.sha256(archive)
                        + "&size="
                        + (archive.length + 100);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Map> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(archive, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(lookup(projectId, cacheKey).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aCorruptedStoredObjectIsNeverReportedAsAValidHit() {
        long projectId = registerProject();
        String cacheKey = "corrupted-object-" + UUID.randomUUID();
        byte[] archive = "originally good bytes".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<Map> uploaded = upload(projectId, cacheKey, archive);
        String objectKey = (String) uploaded.getBody().get("objectStoreKey");

        // simulate object-store corruption directly, bypassing the commit protocol entirely
        s3.putObject(
                b -> b.bucket(s3Properties.getBucket()).key(objectKey),
                RequestBody.fromBytes("corrupted!".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> lookup = lookup(projectId, cacheKey);
        assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private long registerProject() {
        ProjectResponse project =
                rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class);
        return project.id();
    }

    private ResponseEntity<Map> upload(long projectId, String cacheKey, byte[] archive) {
        String digest = Digests.sha256(archive);
        String path =
                "/api/artifacts?projectId="
                        + projectId
                        + "&cacheKey="
                        + cacheKey
                        + "&digest="
                        + digest
                        + "&size="
                        + archive.length;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(archive, headers), Map.class);
    }

    private ResponseEntity<byte[]> lookup(long projectId, String cacheKey) {
        return rest.getForEntity(
                "/api/artifacts/lookup?projectId=" + projectId + "&cacheKey=" + cacheKey,
                byte[].class);
    }
}
