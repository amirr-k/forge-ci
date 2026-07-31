package dev.forgeci.controlplane.api;

import dev.forgeci.controlplane.api.dto.ArtifactResponse;
import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.service.BuildService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BuildController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final BuildService buildService;

    public BuildController(BuildService buildService) {
        this.buildService = buildService;
    }

    @PostMapping("/api/projects/{id}/builds")
    @ResponseStatus(HttpStatus.CREATED)
    public BuildResponse create(
            @PathVariable("id") Long projectId, @Valid @RequestBody BuildCreationRequest request) {
        return BuildResponse.from(buildService.createBuild(projectId, request));
    }

    @GetMapping("/api/projects/{id}/builds")
    public Page<BuildResponse> history(
            @PathVariable("id") Long projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Page<Build> builds =
                buildService.history(projectId, PageRequest.of(Math.max(page, 0), boundedSize));
        return builds.map(BuildResponse::from);
    }

    @GetMapping("/api/builds/{id}")
    public BuildResponse get(@PathVariable("id") Long buildId) {
        return BuildResponse.from(buildService.get(buildId));
    }

    @PostMapping("/api/builds/{id}/cancel")
    public BuildResponse cancel(@PathVariable("id") Long buildId) {
        return BuildResponse.from(buildService.cancel(buildId));
    }

    @GetMapping("/api/builds/{id}/artifacts")
    public List<ArtifactResponse> artifacts(@PathVariable("id") Long buildId) {
        return buildService.artifacts(buildId).stream().map(ArtifactResponse::from).toList();
    }
}
