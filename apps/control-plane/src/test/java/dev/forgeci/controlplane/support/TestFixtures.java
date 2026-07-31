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

    /** Two independent tasks with no dependency edge between them — for parallel-claim tests. */
    public static PlanSubmissionRequest twoIndependentTaskPlan(String revision, String baseRevision) {
        String suffix = UUID.randomUUID().toString();
        return new PlanSubmissionRequest(
                revision,
                baseRevision,
                false,
                List.of("services/alpha/src/A.java", "services/beta/src/B.java"),
                List.of(
                        new TaskDefinitionRequest(
                                "alpha:build",
                                List.of(),
                                "sha256:alpha-" + suffix,
                                "source changed",
                                shellCommand("alpha"),
                                List.of("build/alpha/**"),
                                List.of(),
                                60),
                        new TaskDefinitionRequest(
                                "beta:build",
                                List.of(),
                                "sha256:beta-" + suffix,
                                "source changed",
                                shellCommand("beta"),
                                List.of("build/beta/**"),
                                List.of(),
                                60)),
                List.of());
    }

    /** A single task using the given cache key — for cache-hit-reuse tests across two builds. */
    public static PlanSubmissionRequest singleTaskPlan(String revision, String baseRevision, String taskName, String cacheKey) {
        return new PlanSubmissionRequest(
                revision,
                baseRevision,
                false,
                List.of("services/solo/src/Solo.java"),
                List.of(
                        new TaskDefinitionRequest(
                                taskName,
                                List.of(),
                                cacheKey,
                                "source changed",
                                shellCommand(taskName.replace(':', '-')),
                                List.of("build/solo/**"),
                                List.of(),
                                60)),
                List.of());
    }

    /** A single task with a short (1s) declared timeout — for lease-expiry/crash-recovery tests that need a fast lease. */
    public static PlanSubmissionRequest singleTaskPlanWithShortTimeout(String revision, String baseRevision, String taskName, String cacheKey) {
        return new PlanSubmissionRequest(
                revision,
                baseRevision,
                false,
                List.of("services/solo/src/Solo.java"),
                List.of(
                        new TaskDefinitionRequest(
                                taskName,
                                List.of(),
                                cacheKey,
                                "source changed",
                                shellCommand(taskName.replace(':', '-')),
                                List.of("build/solo/**"),
                                List.of(),
                                1)),
                List.of());
    }

    /** Same shape as {@link #twoTaskPlan}, but with cache keys unique to this call — safe to reuse across tests. */
    public static PlanSubmissionRequest twoTaskPlanWithUniqueKeys(String revision, String baseRevision, String suffix) {
        return new PlanSubmissionRequest(
                revision,
                baseRevision,
                false,
                List.of("services/pricing/src/PriceCalculator.java"),
                List.of(
                        new TaskDefinitionRequest(
                                "pricing:build",
                                List.of(),
                                "sha256:pricing-" + suffix,
                                "source changed",
                                shellCommand("pricing"),
                                List.of("build/pricing/**"),
                                List.of(),
                                60),
                        new TaskDefinitionRequest(
                                "checkout:integration",
                                List.of("pricing:build"),
                                "sha256:checkout-" + suffix,
                                "pricing:build output may change",
                                shellCommand("checkout"),
                                List.of("build/checkout/**"),
                                List.of(),
                                60)),
                List.of("catalog:build"));
    }

    /**
     * {@code trunk:build} feeds {@code downstream:build} and so has a longer remaining critical
     * path than the standalone {@code leaf:build}, even though both are immediately ready — for
     * testing the scheduler's critical-path-then-FIFO tie-break.
     */
    public static PlanSubmissionRequest criticalPathPlan(String revision, String baseRevision, String suffix) {
        return new PlanSubmissionRequest(
                revision,
                baseRevision,
                false,
                List.of("services/trunk/src/T.java", "services/leaf/src/L.java"),
                List.of(
                        new TaskDefinitionRequest(
                                "trunk:build",
                                List.of(),
                                "sha256:trunk-" + suffix,
                                "source changed",
                                shellCommand("trunk"),
                                List.of("build/trunk/**"),
                                List.of(),
                                60),
                        new TaskDefinitionRequest(
                                "downstream:build",
                                List.of("trunk:build"),
                                "sha256:downstream-" + suffix,
                                "trunk:build output may change",
                                shellCommand("downstream"),
                                List.of("build/downstream/**"),
                                List.of(),
                                60),
                        new TaskDefinitionRequest(
                                "leaf:build",
                                List.of(),
                                "sha256:leaf-" + suffix,
                                "source changed",
                                shellCommand("leaf"),
                                List.of("build/leaf/**"),
                                List.of(),
                                60)),
                List.of());
    }

    /** A shell command that deterministically produces one output file under {@code build/<name>/}. */
    public static List<String> shellCommand(String name) {
        return List.of("/bin/sh", "-c", "mkdir -p build/" + name + " && echo built > build/" + name + "/out.txt");
    }
}
