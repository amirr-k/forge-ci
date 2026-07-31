package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.cache.Digests;
import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.api.dto.PlanSubmissionResponse;
import dev.forgeci.controlplane.api.dto.ProjectResponse;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.TestFixtures;
import dev.forgeci.protocol.ClaimedTaskResponse;
import dev.forgeci.protocol.TaskResultReportRequest;
import dev.forgeci.protocol.WorkerRegistrationRequest;
import dev.forgeci.protocol.WorkerRegistrationResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Simulates the worker side of the protocol with plain HTTP calls — proving the control plane's
 * scheduling, dependency, and cache-reuse logic end to end without needing a real Docker-executing
 * worker process for every scenario (that's what {@code apps/worker}'s own tests and the Compose
 * demo are for). No Kafka is involved anywhere here, matching phase 5's "prove the direct path
 * first" ordering.
 *
 * <p>{@code claim} is deliberately a single global priority queue across every build in the
 * system (see spec/reference/architecture.md#scheduler) — it is not scoped to "this test's"
 * build. Other test classes in this module submit plans/builds of their own (and never claim
 * them, since claiming didn't exist before this phase), which leaves permanently-{@code READY}
 * task runs competing for every worker registered here. Every helper below is written to draw
 * down and harmlessly complete that foreign backlog rather than assume the next claim is "ours".
 */
class WorkerSchedulingIntegrationTest extends ControlPlaneIntegrationTest {

    @Autowired private TestRestTemplate rest;

    @Test
    void oneWorkerCompletesOneTaskEndToEndWithoutKafka() {
        long projectId = registerProject();
        String cacheKey = "sha256:solo-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.singleTaskPlan("rev-solo-1", "rev-0", "solo:build", cacheKey),
                        PlanSubmissionResponse.class);
        BuildResponse build = createBuild(projectId, plan.id());

        long workerId = registerWorker("worker-solo");
        ClaimedTaskResponse task = claimMine(workerId, Set.of("solo:build"));
        assertThat(task.buildId()).isEqualTo(build.id());

        byte[] archive = "solo output".getBytes(StandardCharsets.UTF_8);
        String digest = uploadArtifact(projectId, cacheKey, archive);
        reportResult(task, true, 0, null, digest);

        BuildResponse completed = getBuild(build.id());
        assertThat(completed.state()).isEqualTo(BuildState.SUCCEEDED);

        List<Map> artifacts = rest.getForObject("/api/builds/" + build.id() + "/artifacts", List.class);
        assertThat(artifacts).hasSize(1);
    }

    @Test
    void dependentPromotesAfterItsOnlySubmittedDependencyFinishesEvenWithUnsubmittedDependencies() {
        long projectId = registerProject();
        String suffix = UUID.randomUUID().toString();
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.partialDependencyPlan("rev-partial-" + suffix, "rev-0", suffix),
                        PlanSubmissionResponse.class);
        BuildResponse build = createBuild(projectId, plan.id());

        long workerId = registerWorker("worker-partial-" + suffix);
        ClaimedTaskResponse pricing = claimMine(workerId, Set.of("pricing:build"));
        reportResult(pricing, true, 0, null, null);

        // checkout:integration must become claimable once pricing:build (its only submitted
        // dependency) succeeds — payments:build was never part of this plan and must not block it
        ClaimedTaskResponse checkout = claimMine(workerId, Set.of("checkout:integration"));
        reportResult(checkout, true, 0, null, null);

        awaitBuildState(build.id(), BuildState.SUCCEEDED);
    }

    @Test
    void independentTasksClaimedByTwoWorkersRunInParallel() throws Exception {
        long projectId = registerProject();
        String suffix = UUID.randomUUID().toString();
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.twoIndependentTaskPlan("rev-parallel-" + suffix, "rev-0"),
                        PlanSubmissionResponse.class);
        BuildResponse build = createBuild(projectId, plan.id());
        Set<String> mine = Set.of("alpha:build", "beta:build");

        long worker1 = registerWorker("worker-parallel-1-" + suffix);
        long worker2 = registerWorker("worker-parallel-2-" + suffix);

        Duration simulatedWork = Duration.ofMillis(400);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Instant started = Instant.now();
            Future<ClaimedTaskResponse> f1 = pool.submit(() -> claimExecuteAndReport(worker1, mine, simulatedWork));
            Future<ClaimedTaskResponse> f2 = pool.submit(() -> claimExecuteAndReport(worker2, mine, simulatedWork));
            ClaimedTaskResponse t1 = f1.get(20, TimeUnit.SECONDS);
            ClaimedTaskResponse t2 = f2.get(20, TimeUnit.SECONDS);
            Duration elapsed = Duration.between(started, Instant.now());

            assertThat(t1.taskRunId()).isNotEqualTo(t2.taskRunId());
            assertThat(Set.of(t1.taskName(), t2.taskName())).isEqualTo(mine);
            // run serially this would take ~2x simulatedWork; concurrently it stays well under that
            assertThat(elapsed).isLessThan(simulatedWork.multipliedBy(2).minusMillis(150));
        } finally {
            pool.shutdownNow();
        }

        BuildResponse completed = getBuild(build.id());
        assertThat(completed.state()).isEqualTo(BuildState.SUCCEEDED);
    }

    @Test
    void aDownstreamTaskOnlyBecomesClaimableAfterItsDependencySucceeds() {
        long projectId = registerProject();
        String suffix = UUID.randomUUID().toString();
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.twoTaskPlanWithUniqueKeys("rev-dep-" + suffix, "rev-0", suffix),
                        PlanSubmissionResponse.class);
        createBuild(projectId, plan.id());

        long workerId = registerWorker("worker-dep-" + suffix);

        ClaimedTaskResponse first = claimMine(workerId, Set.of("pricing:build"));

        // the downstream task depends on this one and must not be claimable yet — drain whatever
        // foreign backlog exists and confirm none of it is "checkout:integration" either
        assertNeverClaims(workerId, "checkout:integration");

        reportResult(first, true, 0, null, null);

        ClaimedTaskResponse second = claimMine(workerId, Set.of("checkout:integration"));
        assertThat(second.taskName()).isEqualTo("checkout:integration");
    }

    @Test
    void artifactsProducedInOneBuildAreReusedAsACacheHitInASubsequentBuild() {
        long projectId = registerProject();
        String cacheKey = "sha256:reuse-" + UUID.randomUUID();

        PlanSubmissionResponse plan1 =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.singleTaskPlan("rev-reuse-1", "rev-0", "solo:build", cacheKey),
                        PlanSubmissionResponse.class);
        BuildResponse build1 = createBuild(projectId, plan1.id());
        long workerId = registerWorker("worker-reuse-" + UUID.randomUUID());
        ClaimedTaskResponse task = claimMine(workerId, Set.of("solo:build"));
        byte[] archive = "reused output".getBytes(StandardCharsets.UTF_8);
        String digest = uploadArtifact(projectId, cacheKey, archive);
        reportResult(task, true, 0, null, digest);
        awaitBuildState(build1.id(), BuildState.SUCCEEDED);

        // a second build submitting a task with the same cache key should never reach a worker —
        // it must already be CACHED, so the build completes without anything to claim for it
        PlanSubmissionResponse plan2 =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.singleTaskPlan("rev-reuse-2", "rev-0", "solo:build", cacheKey),
                        PlanSubmissionResponse.class);
        BuildResponse build2 = createBuild(projectId, plan2.id());

        awaitBuildState(build2.id(), BuildState.SUCCEEDED);

        List<Map> artifacts = rest.getForObject("/api/builds/" + build2.id() + "/artifacts", List.class);
        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.get(0).get("digest")).isEqualTo(digest);
    }

    @Test
    void theSchedulerReleasesTheHigherCriticalPathWeightTaskFirst() {
        long projectId = registerProject();
        String suffix = UUID.randomUUID().toString();
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.criticalPathPlan("rev-cp-" + suffix, "rev-0", suffix),
                        PlanSubmissionResponse.class);
        createBuild(projectId, plan.id());

        long workerId = registerWorker("worker-cp-" + suffix, 2);
        Set<String> mine = Set.of("trunk:build", "leaf:build");

        // "trunk:build" feeds a further downstream task and so has a longer remaining critical path
        // than the standalone "leaf:build" — the scheduler must release it first regardless of
        // creation order, per spec/reference/architecture.md#scheduler.
        ClaimedTaskResponse first = claimMine(workerId, mine);
        assertThat(first.taskName()).isEqualTo("trunk:build");

        ClaimedTaskResponse second = claimMine(workerId, mine);
        assertThat(second.taskName()).isEqualTo("leaf:build");
    }

    private ClaimedTaskResponse claimExecuteAndReport(long workerId, Set<String> mine, Duration simulatedWork) throws InterruptedException {
        ClaimedTaskResponse task = claimMine(workerId, mine);
        Thread.sleep(simulatedWork.toMillis());
        reportResult(task, true, 0, null, null);
        return task;
    }

    /** Claims until a task whose name is in {@code mine} shows up, harmlessly completing any foreign task run encountered along the way. */
    private ClaimedTaskResponse claimMine(long workerId, Set<String> mine) {
        for (int i = 0; i < 200; i++) {
            Optional<ClaimedTaskResponse> claimed = claim(workerId);
            if (claimed.isEmpty()) {
                sleepQuietly(25);
                continue;
            }
            ClaimedTaskResponse task = claimed.get();
            if (mine.contains(task.taskName())) {
                return task;
            }
            reportResult(task, true, 0, null, null);
        }
        throw new AssertionError("worker " + workerId + " never got one of " + mine);
    }

    /** Drains and completes foreign backlog while confirming {@code forbiddenTaskName} never shows up. */
    private void assertNeverClaims(long workerId, String forbiddenTaskName) {
        for (int i = 0; i < 20; i++) {
            Optional<ClaimedTaskResponse> claimed = claim(workerId);
            if (claimed.isEmpty()) {
                return;
            }
            assertThat(claimed.get().taskName()).isNotEqualTo(forbiddenTaskName);
            reportResult(claimed.get(), true, 0, null, null);
        }
    }

    private void awaitBuildState(Long buildId, BuildState expected) {
        for (int i = 0; i < 100; i++) {
            if (getBuild(buildId).state() == expected) {
                return;
            }
            sleepQuietly(25);
        }
        throw new AssertionError("build " + buildId + " never reached " + expected + " (was " + getBuild(buildId).state() + ")");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long registerProject() {
        ProjectResponse project = rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class);
        return project.id();
    }

    private BuildResponse createBuild(long projectId, Long planSubmissionId) {
        return rest.postForObject(
                "/api/projects/" + projectId + "/builds", new BuildCreationRequest(planSubmissionId, "manual", 0), BuildResponse.class);
    }

    private BuildResponse getBuild(Long buildId) {
        return rest.getForObject("/api/builds/" + buildId, BuildResponse.class);
    }

    private long registerWorker(String externalId) {
        return registerWorker(externalId, 1);
    }

    private long registerWorker(String externalId, int maxConcurrency) {
        WorkerRegistrationResponse response =
                rest.postForObject(
                        "/api/workers/register",
                        new WorkerRegistrationRequest(externalId, List.of(), maxConcurrency, "test"),
                        WorkerRegistrationResponse.class);
        return response.workerId();
    }

    private Optional<ClaimedTaskResponse> claim(long workerId) {
        ResponseEntity<ClaimedTaskResponse> response =
                rest.postForEntity("/api/workers/" + workerId + "/claim", null, ClaimedTaskResponse.class);
        if (response.getStatusCode() == HttpStatus.NO_CONTENT) {
            return Optional.empty();
        }
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Optional.ofNullable(response.getBody());
    }

    private void reportResult(ClaimedTaskResponse task, boolean success, Integer exitCode, String failureReason, String artifactDigest) {
        ResponseEntity<Void> response =
                rest.postForEntity(
                        "/api/task-runs/" + task.taskRunId() + "/result",
                        new TaskResultReportRequest(task.workerId(), task.leaseToken(), task.attemptId(), success, exitCode, failureReason, artifactDigest),
                        Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private String uploadArtifact(long projectId, String cacheKey, byte[] archive) {
        String digest = Digests.sha256(archive);
        String path = "/api/artifacts?projectId=" + projectId + "&cacheKey=" + cacheKey + "&digest=" + digest + "&size=" + archive.length;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Map> response = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(archive, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return digest;
    }
}
