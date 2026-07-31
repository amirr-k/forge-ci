package dev.forgeci.controlplane.api.dto;

import dev.forgeci.controlplane.domain.Project;
import java.time.Instant;

public record ProjectResponse(
        Long id,
        String name,
        String repositoryIdentity,
        String defaultBranch,
        int configVersion,
        Instant createdAt) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getRepositoryIdentity(),
                project.getDefaultBranch(),
                project.getConfigVersion(),
                project.getCreatedAt());
    }
}
