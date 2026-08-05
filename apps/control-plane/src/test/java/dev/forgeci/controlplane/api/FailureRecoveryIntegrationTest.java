package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.cache.Digests;
import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.api.dto.PlanSubmissionResponse;
import dev.forgeci.controlplane.api.dto.ProjectResponse;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.repository.ArtifactRepository;
import dev.forgeci.controlplane.repository.TaskAttemptRepository;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.RedisTestContainer;
import dev.forgeci.controlplane.support.TestFixtures;
import dev.forgeci.protocol.ClaimedTaskResponse;
import dev.forgeci.protocol.HeartbeatResponse;
import dev.forgeci.protocol.LogChunkRequest;
import dev.forgeci.protocol.TaskResultReportRequest;
import dev.forgeci.protocol.WorkerRegistrationRequest;
import dev.forgeci.protocol.WorkerRegistrationResponse;
import dev.forgeci.testsupport.RecoveryTimer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The seven required failure-recovery scenarios from
 * spec/reference/quality-and-testing.md#test-taxonomy-phase-9--applies-across-all-layers, also
 * required by phase 6. Every scenario here simulates the crashed worker by simply going silent (no
 * more heartbeat/report calls) — exactly what a real crash looks like from the control plane's
 * side, and how {@code WorkerSchedulingIntegrationTest} already proves the direct HTTP protocol
 * without a real Docker-executing worker process.
 *
 * <p>Kafka redelivery of a task-result message (the seventh required test) is already covered by
 * {@code KafkaTaskResultsIntegrationTest#aRedeliveredTaskResultMessageDoesNotReapplyItsEffect} —
 * not duplicated here.
 */
class FailureRecoveryIntegrationTest extends ControlPlaneIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(FailureRecoveryIntegrationTest.class);

    @Autowired private TestRestTemplate rest;
    @Autowired private TaskRunRepository taskRunRepository;
    @Autowired private TaskAttemptRepository taskAttemptRepository;
    @Autowired private ArtifactRepository artifactRepository;

    @Test
    void aBuildCompletesAfterItsWorkerCrashesBeforeDoingAnyWork() {
        long projectId = registerProject();
        String cacheKey = "sha256:crash-before-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                submitShortTimeoutPlan(projectId, "crash-before:build", cacheKey);
        BuildResponse build = createBuild(projectId, plan.id());

        long deadWorker = registerWorker("worker-crash-before-" + UUID.randomUUID());
        long survivor = registerWorker("worker-survivor-before-" + UUID.randomUUID());

        ClaimedTaskResponse firstAttempt = claimMine(deadWorker, "crash-before:build");
        RecoveryTimer timer = RecoveryTimer.startingNow();
        // the "crashed" worker never heartbeats, appends logs, or reports again from here on

        ClaimedTaskResponse secondAttempt = claimMine(survivor, "crash-before:build");
        assertThat(secondAttempt.taskRunId()).isEqualTo(firstAttempt.taskRunId());
        assertThat(secondAttempt.attemptId()).isEqualTo(firstAttempt.attemptId() + 1);
        assertThat(secondAttempt.workerId()).isNotEqualTo(firstAttempt.workerId());

        reportResult(secondAttempt, true, 0, null, null);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);

        Duration recovery = timer.elapsed();
        log.info(
                "recovery time (crash before start -> reassignment -> completion): {} ms",
                recovery.toMillis());
        assertThat(recovery).isLessThan(Duration.ofSeconds(30));
    }

    /**
     * Recovery latency must come from noticing the worker is gone, not from the task's own declared
     * timeout. The task here declares a 60s timeout, so its lease alone would hold the work hostage
     * for over a minute; a crashed worker's attempts are instead reclaimed as soon as it misses
     * enough heartbeats, which is the only thing that makes crash recovery scale to slow tasks.
     */
    @Test
    void recoveryFromACrashDoesNotWaitOutTheTasksOwnTimeout() {
        long projectId = registerProject();
        String cacheKey = "sha256:long-timeout-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.singleTaskPlan(
                                "rev-long-" + UUID.randomUUID(),
                                "rev-0",
                                "long-timeout:build",
                                cacheKey),
                        PlanSubmissionResponse.class);
        BuildResponse build = createBuild(projectId, plan.id());

        long deadWorker = registerWorker("worker-long-dead-" + UUID.randomUUID());
        long survivor = registerWorker("worker-long-survivor-" + UUID.randomUUID());

        ClaimedTaskResponse firstAttempt = claimMine(deadWorker, "long-timeout:build");
        assertThat(firstAttempt.timeoutSeconds()).isGreaterThanOrEqualTo(60);
        RecoveryTimer timer = RecoveryTimer.startingNow();
        // and now the worker simply stops: no heartbeat, no report, no logs

        ClaimedTaskResponse secondAttempt = claimMine(survivor, "long-timeout:build");
        assertThat(secondAttempt.taskRunId()).isEqualTo(firstAttempt.taskRunId());
        assertThat(secondAttempt.workerId()).isNotEqualTo(firstAttempt.workerId());

        reportResult(secondAttempt, true, 0, null, null);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);

        Duration recovery = timer.elapsed();
        log.info("recovery time with a 60s task timeout: {} ms", recovery.toMillis());
        assertThat(recovery).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void aBuildCompletesAfterItsWorkerCrashesMidExecution() {
        long projectId = registerProject();
        String cacheKey = "sha256:crash-during-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                submitShortTimeoutPlan(projectId, "crash-during:build", cacheKey);
        BuildResponse build = createBuild(projectId, plan.id());

        long deadWorker = registerWorker("worker-crash-during-" + UUID.randomUUID());
        long survivor = registerWorker("worker-survivor-during-" + UUID.randomUUID());

        ClaimedTaskResponse firstAttempt = claimMine(deadWorker, "crash-during:build");
        // some real progress happens before the crash — a log chunk lands, then silence
        ResponseEntity<Void> logResponse =
                rest.postForEntity(
                        "/api/task-runs/" + firstAttempt.taskRunId() + "/logs",
                        new LogChunkRequest(
                                firstAttempt.workerId(),
                                firstAttempt.leaseToken(),
                                firstAttempt.attemptId(),
                                List.of("compiling...")),
                        Void.class);
        assertThat(logResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ClaimedTaskResponse secondAttempt = claimMine(survivor, "crash-during:build");
        assertThat(secondAttempt.taskRunId()).isEqualTo(firstAttempt.taskRunId());

        byte[] archive = "crash-during output".getBytes(StandardCharsets.UTF_8);
        String digest = uploadArtifact(projectId, cacheKey, archive);
        reportResult(secondAttempt, true, 0, null, digest);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);

        TaskRun taskRun = taskRunRepository.findById(firstAttempt.taskRunId()).orElseThrow();
        assertThat(taskRun.getState()).isEqualTo(TaskRunState.SUCCEEDED);
        assertThat(taskRun.getAttemptCount()).isEqualTo(2);
        assertThat(taskAttemptRepository.findByTaskRunIdOrderByAttemptNumber(taskRun.getId()))
                .hasSize(2);
    }

    @Test
    void aDuplicateResultReportForTheSameAttemptNeverCreatesADuplicateEffect() {
        long projectId = registerProject();
        String cacheKey = "sha256:dup-result-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.singleTaskPlan(
                                "rev-dup-" + UUID.randomUUID(), "rev-0", "dup:build", cacheKey),
                        PlanSubmissionResponse.class);
        BuildResponse build = createBuild(projectId, plan.id());
        long workerId = registerWorker("worker-dup-" + UUID.randomUUID());
        ClaimedTaskResponse task = claimMine(workerId, "dup:build");

        byte[] archive = "dup output".getBytes(StandardCharsets.UTF_8);
        String digest = uploadArtifact(projectId, cacheKey, archive);
        reportResult(task, true, 0, null, digest);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);

        TaskRun afterFirst = taskRunRepository.findById(task.taskRunId()).orElseThrow();
        var completedAtFirst = afterFirst.getCompletedAt();

        // the same worker retries its HTTP call (e.g. after a network blip) — the report is
        // byte-identical and must be a pure no-op
        reportResult(task, true, 0, null, digest);

        TaskRun afterDuplicate = taskRunRepository.findById(task.taskRunId()).orElseThrow();
        assertThat(afterDuplicate.getState()).isEqualTo(TaskRunState.SUCCEEDED);
        assertThat(afterDuplicate.getCompletedAt()).isEqualTo(completedAtFirst);
        assertThat(afterDuplicate.getAttemptCount()).isEqualTo(1);
        assertThat(taskAttemptRepository.findByTaskRunIdOrderByAttemptNumber(task.taskRunId()))
                .hasSize(1);
        assertThat(artifactRepository.findByDigest(digest)).isPresent();

        List<Map> artifacts =
                rest.getForObject("/api/builds/" + build.id() + "/artifacts", List.class);
        assertThat(artifacts).hasSize(1);
    }

    @Test
    void aLateResultReportedAfterLeaseExpirationIsRejectedAndNeverOverwritesTheAcceptedResult() {
        long projectId = registerProject();
        String cacheKey = "sha256:late-result-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                submitShortTimeoutPlan(projectId, "late-result:build", cacheKey);
        BuildResponse build = createBuild(projectId, plan.id());

        long slowWorker = registerWorker("worker-slow-" + UUID.randomUUID());
        long survivor = registerWorker("worker-survivor-late-" + UUID.randomUUID());

        ClaimedTaskResponse staleAttempt = claimMine(slowWorker, "late-result:build");
        ClaimedTaskResponse freshAttempt = claimMine(survivor, "late-result:build");
        assertThat(freshAttempt.taskRunId()).isEqualTo(staleAttempt.taskRunId());

        byte[] archive = "fresh output".getBytes(StandardCharsets.UTF_8);
        String digest = uploadArtifact(projectId, cacheKey, archive);
        reportResult(freshAttempt, true, 0, null, digest);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);

        TaskRun afterFreshResult =
                taskRunRepository.findById(freshAttempt.taskRunId()).orElseThrow();
        var completedAtFresh = afterFreshResult.getCompletedAt();

        // the crashed worker "comes back" and reports against its now-superseded lease
        ResponseEntity<Map> lateReport =
                rest.postForEntity(
                        "/api/task-runs/" + staleAttempt.taskRunId() + "/result",
                        new TaskResultReportRequest(
                                staleAttempt.workerId(),
                                staleAttempt.leaseToken(),
                                staleAttempt.attemptId(),
                                true,
                                0,
                                null,
                                "sha256:forged"),
                        Map.class);
        assertThat(lateReport.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        TaskRun afterLateReport =
                taskRunRepository.findById(staleAttempt.taskRunId()).orElseThrow();
        assertThat(afterLateReport.getArtifactDigest()).isEqualTo(digest);
        assertThat(afterLateReport.getCompletedAt()).isEqualTo(completedAtFresh);
    }

    @Test
    void anArtifactUploadedBeforeADelayedResultCommitIsNeverDuplicated() {
        long projectId = registerProject();
        String cacheKey = "sha256:delayed-commit-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                submitShortTimeoutPlan(projectId, "delayed-commit:build", cacheKey);
        BuildResponse build = createBuild(projectId, plan.id());

        long originalWorker = registerWorker("worker-delayed-" + UUID.randomUUID());
        long survivor = registerWorker("worker-survivor-delayed-" + UUID.randomUUID());

        ClaimedTaskResponse originalAttempt = claimMine(originalWorker, "delayed-commit:build");
        byte[] archive = "delayed output".getBytes(StandardCharsets.UTF_8);
        // the artifact upload (keyed by cache key, independent of any task run/lease) succeeds...
        String digest = uploadArtifact(projectId, cacheKey, archive);

        // ...but the result report that would have committed it against this attempt never
        // arrives before the lease expires — the task is reassigned
        ClaimedTaskResponse newAttempt = claimMine(survivor, "delayed-commit:build");
        assertThat(newAttempt.taskRunId()).isEqualTo(originalAttempt.taskRunId());

        // the new attempt uploads (byte-identical, so it dedupes to the same artifact row) and
        // reports
        String secondDigest = uploadArtifact(projectId, cacheKey, archive);
        assertThat(secondDigest).isEqualTo(digest);
        reportResult(newAttempt, true, 0, null, secondDigest);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);

        assertThat(artifactRepository.findByDigest(digest)).isPresent();
        List<Map> artifacts =
                rest.getForObject("/api/builds/" + build.id() + "/artifacts", List.class);
        assertThat(artifacts).hasSize(1);

        // the original attempt's delayed result finally arrives — rejected, not re-applied
        ResponseEntity<Map> delayedReport =
                rest.postForEntity(
                        "/api/task-runs/" + originalAttempt.taskRunId() + "/result",
                        new TaskResultReportRequest(
                                originalAttempt.workerId(),
                                originalAttempt.leaseToken(),
                                originalAttempt.attemptId(),
                                true,
                                0,
                                null,
                                digest),
                        Map.class);
        assertThat(delayedReport.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theSystemRecoversAfterARedisFlushDuringAnActiveBuild() {
        long projectId = registerProject();
        String cacheKey = "sha256:redis-flush-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                submitShortTimeoutPlan(projectId, "redis-flush:build", cacheKey);
        BuildResponse build = createBuild(projectId, plan.id());

        long deadWorker = registerWorker("worker-redis-flush-" + UUID.randomUUID());
        long survivor = registerWorker("worker-redis-flush-survivor-" + UUID.randomUUID());

        ClaimedTaskResponse firstAttempt = claimMine(deadWorker, "redis-flush:build");

        // wipes every Redis key, including the lease/heartbeat acceleration entries this phase adds
        // —
        // MySQL's own lease_expiration sweep must recover the build regardless
        flushRedis();

        ClaimedTaskResponse secondAttempt = claimMine(survivor, "redis-flush:build");
        assertThat(secondAttempt.taskRunId()).isEqualTo(firstAttempt.taskRunId());
        reportResult(secondAttempt, true, 0, null, null);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);
    }

    @Test
    void aCrashInjectionRequestIsDeliveredOnTheWorkersNextHeartbeatAndThenCleared() {
        long workerId = registerWorker("worker-crash-injection-" + UUID.randomUUID());

        ResponseEntity<Void> crashRequest =
                rest.postForEntity("/api/workers/" + workerId + "/crash", null, Void.class);
        assertThat(crashRequest.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        HeartbeatResponse first = heartbeat(workerId);
        assertThat(first.shouldCrash()).isTrue();

        // consumed — a worker that (hypothetically) survived a halt request never re-crashes on its
        // own
        HeartbeatResponse second = heartbeat(workerId);
        assertThat(second.shouldCrash()).isFalse();
    }

    private PlanSubmissionResponse submitShortTimeoutPlan(
            long projectId, String taskName, String cacheKey) {
        return rest.postForObject(
                "/api/projects/" + projectId + "/plans",
                TestFixtures.singleTaskPlanWithShortTimeout(
                        "rev-" + UUID.randomUUID(), "rev-0", taskName, cacheKey),
                PlanSubmissionResponse.class);
    }

    /**
     * Claims until a task named {@code taskName} shows up, waiting through
     * lease-expiry/retry-backoff delay if needed. Heartbeats {@code workerId} on every poll — a
     * real worker heartbeats on its own schedule the whole time it's alive, and this test's
     * tightened heartbeat interval would otherwise mark a merely-idle (not crashed) polling worker
     * unhealthy and starve it of claims.
     */
    private ClaimedTaskResponse claimMine(long workerId, String taskName) {
        for (int i = 0; i < 400; i++) {
            heartbeat(workerId);
            Optional<ClaimedTaskResponse> claimed = claim(workerId);
            if (claimed.isEmpty()) {
                sleepQuietly(100);
                continue;
            }
            ClaimedTaskResponse task = claimed.get();
            if (task.taskName().equals(taskName)) {
                return task;
            }
            reportResult(task, true, 0, null, null); // harmlessly complete foreign backlog
        }
        throw new AssertionError("worker " + workerId + " never claimed " + taskName);
    }

    private Optional<ClaimedTaskResponse> claim(long workerId) {
        ResponseEntity<ClaimedTaskResponse> response =
                rest.postForEntity(
                        "/api/workers/" + workerId + "/claim", null, ClaimedTaskResponse.class);
        if (response.getStatusCode() == HttpStatus.NO_CONTENT) {
            return Optional.empty();
        }
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Optional.ofNullable(response.getBody());
    }

    private void reportResult(
            ClaimedTaskResponse task,
            boolean success,
            Integer exitCode,
            String failureReason,
            String artifactDigest) {
        ResponseEntity<Void> response =
                rest.postForEntity(
                        "/api/task-runs/" + task.taskRunId() + "/result",
                        new TaskResultReportRequest(
                                task.workerId(),
                                task.leaseToken(),
                                task.attemptId(),
                                success,
                                exitCode,
                                failureReason,
                                artifactDigest),
                        Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private HeartbeatResponse heartbeat(long workerId) {
        return rest.postForObject(
                "/api/workers/" + workerId + "/heartbeat", null, HeartbeatResponse.class);
    }

    private void awaitBuildState(Long buildId, BuildState expected) {
        for (int i = 0; i < 400; i++) {
            if (getBuild(buildId).state() == expected) {
                return;
            }
            sleepQuietly(100);
        }
        throw new AssertionError(
                "build "
                        + buildId
                        + " never reached "
                        + expected
                        + " (was "
                        + getBuild(buildId).state()
                        + ")");
    }

    private static void flushRedis() {
        try {
            RedisTestContainer.INSTANCE.execInContainer("redis-cli", "FLUSHALL");
        } catch (Exception e) {
            throw new AssertionError("could not flush Redis test container", e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long registerProject() {
        ProjectResponse project =
                rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class);
        return project.id();
    }

    private BuildResponse createBuild(long projectId, Long planSubmissionId) {
        return rest.postForObject(
                "/api/projects/" + projectId + "/builds",
                new BuildCreationRequest(planSubmissionId, "manual", 0),
                BuildResponse.class);
    }

    private BuildResponse getBuild(Long buildId) {
        return rest.getForObject("/api/builds/" + buildId, BuildResponse.class);
    }

    private long registerWorker(String externalId) {
        WorkerRegistrationResponse response =
                rest.postForObject(
                        "/api/workers/register",
                        new WorkerRegistrationRequest(externalId, List.of(), 1, "test"),
                        WorkerRegistrationResponse.class);
        return response.workerId();
    }

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
}
