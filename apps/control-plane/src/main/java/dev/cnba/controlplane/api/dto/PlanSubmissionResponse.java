package dev.cnba.controlplane.api.dto;

import dev.cnba.controlplane.domain.PlanSubmission;

public record PlanSubmissionResponse(
        Long id,
        Long projectId,
        String revision,
        String baseRevision,
        boolean fullBuild,
        int taskCount) {

    public static PlanSubmissionResponse from(PlanSubmission submission) {
        return new PlanSubmissionResponse(
                submission.getId(),
                submission.getProject().getId(),
                submission.getRevision(),
                submission.getBaseRevision(),
                submission.isFullBuild(),
                submission.getTasks().size());
    }
}
