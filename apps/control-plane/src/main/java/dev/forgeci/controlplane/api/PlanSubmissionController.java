package dev.forgeci.controlplane.api;

import dev.forgeci.controlplane.api.dto.PlanSubmissionRequest;
import dev.forgeci.controlplane.api.dto.PlanSubmissionResponse;
import dev.forgeci.controlplane.service.PlanSubmissionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlanSubmissionController {

    private final PlanSubmissionService planSubmissionService;

    public PlanSubmissionController(PlanSubmissionService planSubmissionService) {
        this.planSubmissionService = planSubmissionService;
    }

    @PostMapping("/api/projects/{id}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanSubmissionResponse submit(
            @PathVariable("id") Long projectId, @Valid @RequestBody PlanSubmissionRequest request) {
        return PlanSubmissionResponse.from(planSubmissionService.submit(projectId, request));
    }
}
