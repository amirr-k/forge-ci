package dev.forgeci.controlplane.api;

import dev.forgeci.controlplane.api.dto.ArtifactResponse;
import dev.forgeci.controlplane.domain.Artifact;
import dev.forgeci.controlplane.service.RemoteArtifactService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The remote half of the content-addressed artifact protocol: a CLI or worker uploads a task's
 * archive with the digest and size it already computed, and looks artifacts up by the same cache
 * key {@code libs/cache} uses locally, so the relocatable-key property carries through unchanged.
 */
@RestController
public class ArtifactController {

    private final RemoteArtifactService remoteArtifactService;

    public ArtifactController(RemoteArtifactService remoteArtifactService) {
        this.remoteArtifactService = remoteArtifactService;
    }

    @PostMapping(value = "/api/artifacts", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ArtifactResponse upload(
            @RequestParam long projectId,
            @RequestParam String cacheKey,
            @RequestParam String digest,
            @RequestParam long size,
            @RequestBody byte[] content) {
        Artifact artifact = remoteArtifactService.commit(projectId, cacheKey, digest, size, content);
        return ArtifactResponse.from(artifact);
    }

    @GetMapping(value = "/api/artifacts/lookup", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> lookup(@RequestParam long projectId, @RequestParam String cacheKey) {
        RemoteArtifactService.DownloadResult result = remoteArtifactService.download(cacheKey, projectId);
        return ResponseEntity.ok()
                .header("X-Artifact-Digest", result.digest())
                .header("X-Artifact-Size", String.valueOf(result.content().length))
                .body(result.content());
    }
}
