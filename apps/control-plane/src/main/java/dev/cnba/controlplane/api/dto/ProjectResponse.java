package dev.cnba.controlplane.api.dto;

import dev.cnba.controlplane.domain.Project;
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
