package dev.forgeci.controlplane.api;

import dev.forgeci.controlplane.api.dto.ProjectRegistrationRequest;
import dev.forgeci.controlplane.api.dto.ProjectResponse;
import dev.forgeci.controlplane.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping("/api/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse register(@Valid @RequestBody ProjectRegistrationRequest request) {
        return ProjectResponse.from(projectService.register(request));
    }
}
