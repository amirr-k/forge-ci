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
                        new TaskDefinitionRequest(
                                "pricing:build",
                                List.of(),
                                "sha256:pricing",
                                "source changed",
                                shellCommand("pricing"),
                                List.of("build/pricing/**"),
                                List.of(),
                                60),
                        new TaskDefinitionRequest(
                                "checkout:integration",
                                List.of("pricing:build"),
                                "sha256:checkout",
                                "pricing:build output may change",
                                shellCommand("checkout"),
                                List.of("build/checkout/**"),
                                List.of(),
                                60)),
                List.of("catalog:build"));
    }

    /** A shell command that deterministically produces one output file under {@code build/<name>/}. */
    public static List<String> shellCommand(String name) {
        return List.of("/bin/sh", "-c", "mkdir -p build/" + name + " && echo built > build/" + name + "/out.txt");
    }
}
