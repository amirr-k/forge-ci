package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.cache.Digests;
import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.api.dto.PlanSubmissionResponse;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.ProtocolTestClient;
import dev.forgeci.controlplane.support.TestFixtures;
import dev.forgeci.protocol.ClaimedTaskResponse;
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
import org.junit.jupiter.api.BeforeEach;
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
 * <p>{@code claim} is deliberately a single global priority queue across every build in the system
 * (see spec/reference/architecture.md#scheduler) — it is not scoped to "this test's" build, so
 * every claim here goes through {@link ProtocolTestClient#claimOneOf}, which heartbeats like a live
 * worker and draws down foreign backlog instead of assuming the next claim is ours. Each test also
 * finishes the build it started: a task left leased here is re-queued on lease expiry a minute
 * later, into whichever unrelated test class happens to be running by then.
 */
class WorkerSchedulingIntegrationTest extends ControlPlaneIntegrationTest {

    @Autowired private TestRestTemplate rest;

    private ProtocolTestClient client;

    @BeforeEach
    void setUp() {
        client = new ProtocolTestClient(rest);
    }

    @Test
    void oneWorkerCompletesOneTaskEndToEndWithoutKafka() {
        long projectId = client.registerProject();
        String cacheKey = "sha256:solo-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                client.submitPlan(
                        projectId,
                        TestFixtures.singleTaskPlan("rev-solo-1", "rev-0", "solo:build", cacheKey));
        BuildResponse build = client.createBuild(projectId, plan.id());

        long workerId = client.registerWorker("worker-solo-" + UUID.randomUUID());
        ClaimedTaskResponse task = client.claimNamed(workerId, "solo:build");
        assertThat(task.buildId()).isEqualTo(build.id());

        byte[] archive = "solo output".getBytes(StandardCharsets.UTF_8);
        String digest = uploadArtifact(projectId, cacheKey, archive);
        client.reportResult(task, true, 0, null, digest);

        client.awaitBuildState(build.id(), BuildState.SUCCEEDED);

        List<Map> artifacts =
                rest.getForObject("/api/builds/" + build.id() + "/artifacts", List.class);
        assertThat(artifacts).hasSize(1);
    }

    @Test
    void aCacheHitOnATaskWithADependentStillCascadesToThatDependent() {
        long projectId = client.registerProject();
        String suffix = UUID.randomUUID().toString();

        PlanSubmissionResponse plan1 =
                client.submitPlan(
                        projectId,
                        TestFixtures.twoTaskPlanWithUniqueKeys("rev-cascade-1", "rev-0", suffix));
        BuildResponse build1 = client.createBuild(projectId, plan1.id());
        long workerId = client.registerWorker("worker-cascade-" + suffix);
        ClaimedTaskResponse pricing1 = client.claimNamed(workerId, "pricing:build");
        String pricingDigest =
                uploadArtifact(
                        projectId,
                        "sha256:pricing-" + suffix,
                        "pricing output".getBytes(StandardCharsets.UTF_8));
        client.reportResult(pricing1, true, 0, null, pricingDigest);
        ClaimedTaskResponse checkout1 = client.claimNamed(workerId, "checkout:integration");
        String checkoutDigest =
                uploadArtifact(
                        projectId,
                        "sha256:checkout-" + suffix,
                        "checkout output".getBytes(StandardCharsets.UTF_8));
        client.reportResult(checkout1, true, 0, null, checkoutDigest);
        client.awaitBuildState(build1.id(), BuildState.SUCCEEDED);

        // resubmitting the identical plan: pricing:build hits cache immediately at build creation
        // (zero in-plan dependencies) — checkout:integration must still be promoted and checked
        // for its own cache hit rather than being stranded PENDING behind a task that never ran
        PlanSubmissionResponse plan2 =
                client.submitPlan(
                        projectId,
                        TestFixtures.twoTaskPlanWithUniqueKeys("rev-cascade-2", "rev-0", suffix));
        BuildResponse build2 = client.createBuild(projectId, plan2.id());

        client.awaitBuildState(build2.id(), BuildState.SUCCEEDED);
    }

    @Test
    void dependentPromotesAfterItsOnlySubmittedDependencyFinishesEvenWithUnsubmittedDependencies() {
        long projectId = client.registerProject();
        String suffix = UUID.randomUUID().toString();
        PlanSubmissionResponse plan =
                client.submitPlan(
                        projectId,
                        TestFixtures.partialDependencyPlan(
                                "rev-partial-" + suffix, "rev-0", suffix));
        BuildResponse build = client.createBuild(projectId, plan.id());

        long workerId = client.registerWorker("worker-partial-" + suffix);
        ClaimedTaskResponse pricing = client.claimNamed(workerId, "pricing:build");
        client.reportResult(pricing, true, 0, null, null);

        // checkout:integration must become claimable once pricing:build (its only submitted
        // dependency) succeeds — payments:build was never part of this plan and must not block it
        ClaimedTaskResponse checkout = client.claimNamed(workerId, "checkout:integration");
        client.reportResult(checkout, true, 0, null, null);

        client.awaitBuildState(build.id(), BuildState.SUCCEEDED);
    }

    @Test
    void independentTasksClaimedByTwoWorkersRunInParallel() throws Exception {
        long projectId = client.registerProject();
        String suffix = UUID.randomUUID().toString();
        PlanSubmissionResponse plan =
                client.submitPlan(
                        projectId,
                        TestFixtures.twoIndependentTaskPlan("rev-parallel-" + suffix, "rev-0"));
        BuildResponse build = client.createBuild(projectId, plan.id());
        Set<String> mine = Set.of("alpha:build", "beta:build");

        long worker1 = client.registerWorker("worker-parallel-1-" + suffix);
        long worker2 = client.registerWorker("worker-parallel-2-" + suffix);

        Duration simulatedWork = Duration.ofMillis(400);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Instant started = Instant.now();
            Future<ClaimedTaskResponse> f1 =
                    pool.submit(() -> claimExecuteAndReport(worker1, mine, simulatedWork));
            Future<ClaimedTaskResponse> f2 =
                    pool.submit(() -> claimExecuteAndReport(worker2, mine, simulatedWork));
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

        client.awaitBuildState(build.id(), BuildState.SUCCEEDED);
    }

    @Test
    void aDownstreamTaskOnlyBecomesClaimableAfterItsDependencySucceeds() {
        long projectId = client.registerProject();
        String suffix = UUID.randomUUID().toString();
        PlanSubmissionResponse plan =
                client.submitPlan(
                        projectId,
                        TestFixtures.twoTaskPlanWithUniqueKeys(
                                "rev-dep-" + suffix, "rev-0", suffix));
        BuildResponse build = client.createBuild(projectId, plan.id());

        long workerId = client.registerWorker("worker-dep-" + suffix);

        ClaimedTaskResponse first = client.claimNamed(workerId, "pricing:build");

        // the downstream task depends on this one and must not be claimable yet — drain whatever
        // foreign backlog exists and confirm none of it is "checkout:integration" either
        assertNeverClaims(workerId, "checkout:integration");

        client.reportResult(first, true, 0, null, null);

        ClaimedTaskResponse second = client.claimNamed(workerId, "checkout:integration");
        assertThat(second.taskName()).isEqualTo("checkout:integration");

        client.reportResult(second, true, 0, null, null);
        client.awaitBuildState(build.id(), BuildState.SUCCEEDED);
    }

    @Test
    void artifactsProducedInOneBuildAreReusedAsACacheHitInASubsequentBuild() {
        long projectId = client.registerProject();
        String cacheKey = "sha256:reuse-" + UUID.randomUUID();

        PlanSubmissionResponse plan1 =
                client.submitPlan(
                        projectId,
                        TestFixtures.singleTaskPlan(
                                "rev-reuse-1", "rev-0", "solo:build", cacheKey));
        BuildResponse build1 = client.createBuild(projectId, plan1.id());
        long workerId = client.registerWorker("worker-reuse-" + UUID.randomUUID());
        ClaimedTaskResponse task = client.claimNamed(workerId, "solo:build");
        byte[] archive = "reused output".getBytes(StandardCharsets.UTF_8);
        String digest = uploadArtifact(projectId, cacheKey, archive);
        client.reportResult(task, true, 0, null, digest);
        client.awaitBuildState(build1.id(), BuildState.SUCCEEDED);

        // a second build submitting a task with the same cache key should never reach a worker —
        // it must already be CACHED, so the build completes without anything to claim for it
        PlanSubmissionResponse plan2 =
                client.submitPlan(
                        projectId,
                        TestFixtures.singleTaskPlan(
                                "rev-reuse-2", "rev-0", "solo:build", cacheKey));
        BuildResponse build2 = client.createBuild(projectId, plan2.id());

        client.awaitBuildState(build2.id(), BuildState.SUCCEEDED);

        List<Map> artifacts =
                rest.getForObject("/api/builds/" + build2.id() + "/artifacts", List.class);
        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.get(0).get("digest")).isEqualTo(digest);
    }

    @Test
    void theSchedulerReleasesTheHigherCriticalPathWeightTaskFirst() {
        long projectId = client.registerProject();
        String suffix = UUID.randomUUID().toString();
        PlanSubmissionResponse plan =
                client.submitPlan(
                        projectId,
                        TestFixtures.criticalPathPlan("rev-cp-" + suffix, "rev-0", suffix));
        BuildResponse build = client.createBuild(projectId, plan.id());

        long workerId = client.registerWorker("worker-cp-" + suffix, 2);
        Set<String> mine = Set.of("trunk:build", "leaf:build");

        // "trunk:build" feeds a further downstream task and so has a longer remaining critical path
        // than the standalone "leaf:build" — the scheduler must release it first regardless of
        // creation order, per spec/reference/architecture.md#scheduler.
        ClaimedTaskResponse first = client.claimOneOf(workerId, mine);
        assertThat(first.taskName()).isEqualTo("trunk:build");

        ClaimedTaskResponse second = client.claimOneOf(workerId, mine);
        assertThat(second.taskName()).isEqualTo("leaf:build");

        client.reportResult(first, true, 0, null, null);
        client.reportResult(second, true, 0, null, null);
        ClaimedTaskResponse downstream = client.claimNamed(workerId, "downstream:build");
        client.reportResult(downstream, true, 0, null, null);
        client.awaitBuildState(build.id(), BuildState.SUCCEEDED);
    }

    private ClaimedTaskResponse claimExecuteAndReport(
            long workerId, Set<String> mine, Duration simulatedWork) throws InterruptedException {
        ClaimedTaskResponse task = client.claimOneOf(workerId, mine);
        Thread.sleep(simulatedWork.toMillis());
        client.reportResult(task, true, 0, null, null);
        return task;
    }

    /**
     * Uploads with the cache key spelled exactly as the plan declares it. Deliberately not the
     * shared client's upload, which percent-encodes the key: that round-trips fine against its own
     * lookup, but commits an entry the cache-hit check here never matches, so the second build in
     * both reuse tests below misses and stalls waiting for a worker.
     */
    private String uploadArtifact(long projectId, String cacheKey, byte[] archive) {
        String digest = Digests.sha256(archive);
        String path =
                "/api/artifacts?projectId="
                        + projectId
                        + "&cacheKey="
                        + cacheKey
                        + "&digest="
                        + digest
                        + "&size="
                        + archive.length;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Map> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(archive, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return digest;
    }

    /**
     * Drains and completes foreign backlog while confirming {@code forbiddenTaskName} never shows
     * up.
     */
    private void assertNeverClaims(long workerId, String forbiddenTaskName) {
        for (int i = 0; i < 20; i++) {
            client.heartbeat(workerId);
            Optional<ClaimedTaskResponse> claimed = client.claim(workerId);
            if (claimed.isEmpty()) {
                return;
            }
            assertThat(claimed.get().taskName()).isNotEqualTo(forbiddenTaskName);
            client.drain(claimed.get());
        }
    }
}
