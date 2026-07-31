package dev.forgeci.controlplane.api.dto;

import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.BuildState;
import java.time.Instant;

public record BuildResponse(
        Long id,
        Long projectId,
        Long planSubmissionId,
        String revision,
        String baseRevision,
        BuildState state,
        int requestedWorkerCount,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {

    public static BuildResponse from(Build build) {
        return new BuildResponse(
                build.getId(),
                build.getProject().getId(),
                build.getPlanSubmission().getId(),
                build.getRevision(),
                build.getBaseRevision(),
                build.getState(),
                build.getRequestedWorkerCount(),
                build.getCreatedAt(),
                build.getStartedAt(),
                build.getCompletedAt());
    }
}
