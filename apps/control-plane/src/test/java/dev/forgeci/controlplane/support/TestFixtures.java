package dev.forgeci.controlplane.support;

import dev.forgeci.controlplane.api.dto.PlanSubmissionRequest;
import dev.forgeci.controlplane.api.dto.ProjectRegistrationRequest;
import dev.forgeci.controlplane.api.dto.TaskDefinitionRequest;
import java.util.List;
import java.util.UUID;

public final class TestFixtures {

    private TestFixtures() {}

    public static ProjectRegistrationRequest project() {
        return new ProjectRegistrationRequest(
                "dispatch-lab-" + UUID.randomUUID(), "git@example.com:example/dispatch-lab.git", "main", 1);
    }

    /** Two independent tasks (no dependency edge) plus one dependent on the first. */
    public static PlanSubmissionRequest twoTaskPlan(String revision, String baseRevision) {
        return new PlanSubmissionRequest(
                revision,
                baseRevision,
                false,
                List.of("services/pricing/src/PriceCalculator.java"),
                List.of(
                        new TaskDefinitionRequest("pricing:build", List.of(), "sha256:pricing", "source changed"),
                        new TaskDefinitionRequest(
                                "checkout:integration",
                                List.of("pricing:build"),
                                "sha256:checkout",
                                "pricing:build output may change")),
                List.of("catalog:build"));
    }
}
