package dev.forgeci.controlplane.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record PlanSubmissionRequest(
        @NotBlank String revision,
        @NotBlank String baseRevision,
        boolean fullBuild,
        @NotNull List<String> changedPaths,
        @NotNull @Valid List<TaskDefinitionRequest> tasks,
        @NotNull List<String> unaffectedTasks) {

    public PlanSubmissionRequest {
        changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        unaffectedTasks = unaffectedTasks == null ? List.of() : List.copyOf(unaffectedTasks);
    }
}
