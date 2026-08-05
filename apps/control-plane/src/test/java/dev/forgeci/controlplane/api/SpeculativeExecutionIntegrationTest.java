package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.api.dto.PlanSubmissionResponse;
import dev.forgeci.controlplane.api.dto.ProjectResponse;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.domain.TaskAttempt;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.repository.TaskAttemptRepository;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.TestFixtures;
import dev.forgeci.protocol.ClaimedTaskResponse;
import dev.forgeci.protocol.HeartbeatResponse;
import dev.forgeci.protocol.TaskResultReportRequest;
import dev.forgeci.protocol.WorkerRegistrationRequest;
import dev.forgeci.protocol.WorkerRegistrationResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Bounded straggler-aware speculative execution: a task run that is running far past its own
 * historical duration gets a second attempt on an otherwise-idle worker, and exactly one of the two
 * results is ever accepted.
 *
 * <p>The guarantee under test is deliberately <em>not</em> exactly-once execution — both attempts
 * genuinely run the command. What is guaranteed is idempotent acceptance: one winning attempt, one
 * artifact, one set of dependents released, and a hard rejection for whichever attempt reports
 * second.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "forge.scheduler.speculation.enabled=true",
            // threshold is max(min-elapsed, median x multiplier); the history this test builds is
            // only milliseconds long, so the 1s floor is what actually gates speculation here
            "forge.scheduler.speculation.min-elapsed-ms=1000",
            "forge.scheduler.speculation.multiplier=1.0",
            "forge.scheduler.speculation.max-per-build=4"
        })
class SpeculativeExecutionIntegrationTest extends ControlPlaneIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private TaskRunRepository taskRunRepository;
    @Autowired private TaskAttemptRepository taskAttemptRepository;

    @Test
    void aStragglerIsDuplicatedOnAnIdleWorkerAndTheWinningAttemptIsTheOneThatReportsFirst() {
        long projectId = registerProject();
        String taskName = "spec:build";
        seedDurationHistory(projectId, taskName, 3);

        BuildResponse build = createBuild(projectId, taskName);
        long stragglerWorker = registerWorker("worker-straggler-" + UUID.randomUUID());
        long idleWorker = registerWorker("worker-idle-" + UUID.randomUUID());

        ClaimedTaskResponse original = claimMine(stragglerWorker, taskName);
        // the straggler never reports; it just keeps heartbeating, which is what makes this a
        // slowdown rather than a crash — the lease stays valid the whole time
        ClaimedTaskResponse speculative =
                claimSameTaskRun(idleWorker, original.taskRunId(), stragglerWorker);

        assertThat(speculative.taskRunId()).isEqualTo(original.taskRunId());
        assertThat(speculative.attemptId()).isNotEqualTo(original.attemptId());
        assertThat(speculative.workerId()).isNotEqualTo(original.workerId());
        assertThat(speculative.leaseToken()).isNotEqualTo(original.leaseToken());

        List<TaskAttempt> attempts =
                taskAttemptRepository.findByTaskRunIdOrderByAttemptNumber(original.taskRunId());
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).isSpeculative()).isFalse();
        assertThat(attempts.get(1).isSpeculative()).isTrue();
        // the run itself never left RUNNING: a speculative attempt is invisible to the task run's
        // own state machine
        assertThat(taskRunRepository.findById(original.taskRunId()).orElseThrow().getState())
                .isEqualTo(TaskRunState.RUNNING);

        // the duplicate finishes first and wins
        assertThat(report(speculative, true)).isEqualTo(HttpStatus.NO_CONTENT);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);

        TaskRun finished = taskRunRepository.findById(original.taskRunId()).orElseThrow();
        assertThat(finished.getState()).isEqualTo(TaskRunState.SUCCEEDED);
        assertThat(finished.getWinningAttemptNumber()).isEqualTo(speculative.attemptId());

        // and the original's eventual report is rejected outright rather than re-applied
        assertThat(report(original, true)).isEqualTo(HttpStatus.FORBIDDEN);
        TaskRun afterLateReport = taskRunRepository.findById(original.taskRunId()).orElseThrow();
        assertThat(afterLateReport.getWinningAttemptNumber()).isEqualTo(speculative.attemptId());
        assertThat(afterLateReport.getCompletedAt()).isEqualTo(finished.getCompletedAt());
        assertThat(getBuild(build.id()).state()).isEqualTo(BuildState.SUCCEEDED);
    }

    @Test
    void theOriginalCanStillWinAndThenTheSpeculativeDuplicateIsTheOneRejected() {
        long projectId = registerProject();
        String taskName = "spec-original-wins:build";
        seedDurationHistory(projectId, taskName, 3);

        BuildResponse build = createBuild(projectId, taskName);
        long stragglerWorker = registerWorker("worker-slow-" + UUID.randomUUID());
        long idleWorker = registerWorker("worker-spare-" + UUID.randomUUID());

        ClaimedTaskResponse original = claimMine(stragglerWorker, taskName);
        ClaimedTaskResponse speculative =
                claimSameTaskRun(idleWorker, original.taskRunId(), stragglerWorker);

        assertThat(report(original, true)).isEqualTo(HttpStatus.NO_CONTENT);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);

        TaskRun finished = taskRunRepository.findById(original.taskRunId()).orElseThrow();
        assertThat(finished.getWinningAttemptNumber()).isEqualTo(original.attemptId());
        // the losing attempt is retired the moment a winner exists, so its own report is refused
        assertThat(report(speculative, true)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        taskAttemptRepository
                                .findByTaskRunIdAndAttemptNumber(
                                        original.taskRunId(), speculative.attemptId())
                                .orElseThrow()
                                .getState())
                .isEqualTo(TaskRunState.SKIPPED);
    }

    @Test
    void speculationNeverTakesASlotThatUnstartedWorkCouldHaveUsed() {
        long projectId = registerProject();
        seedDurationHistory(projectId, "alpha:build", 3);

        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.twoIndependentTaskPlan("rev-" + UUID.randomUUID(), "rev-0"),
                        PlanSubmissionResponse.class);
        BuildResponse build = createBuild(projectId, plan.id());

        long stragglerWorker = registerWorker("worker-busy-" + UUID.randomUUID());
        long secondWorker = registerWorker("worker-second-" + UUID.randomUUID());

        ClaimedTaskResponse straggler = claimMine(stragglerWorker, "alpha:build");
        sleepQuietly(1500); // past the speculation threshold, had there been nothing else to do

        // the second worker has real, never-started work available, so that is what it must get
        ClaimedTaskResponse next = claimMine(secondWorker, "beta:build");
        assertThat(next.taskRunId()).isNotEqualTo(straggler.taskRunId());
        assertThat(taskAttemptRepository.findByTaskRunIdOrderByAttemptNumber(straggler.taskRunId()))
                .hasSize(1);

        assertThat(report(next, true)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(report(straggler, true)).isEqualTo(HttpStatus.NO_CONTENT);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);
    }

    /**
     * Speculation needs something to be slow relative to. A task the project has never completed
     * before has no median duration, so it is left alone however long it runs — otherwise the first
     * run of every genuinely expensive task would be duplicated on principle.
     */
    @Test
    void aTaskWithNoDurationHistoryIsNeverTreatedAsAStraggler() {
        long projectId = registerProject();
        String taskName = "spec-no-history:build";

        BuildResponse build = createBuild(projectId, taskName);
        long stragglerWorker = registerWorker("worker-unknown-" + UUID.randomUUID());
        long idleWorker = registerWorker("worker-waiting-" + UUID.randomUUID());

        ClaimedTaskResponse original = claimMine(stragglerWorker, taskName);
        sleepQuietly(2000); // well past the 1s floor

        for (int i = 0; i < 10; i++) {
            heartbeat(idleWorker);
            assertThat(claim(idleWorker)).isEmpty();
            sleepQuietly(100);
        }
        assertThat(taskAttemptRepository.findByTaskRunIdOrderByAttemptNumber(original.taskRunId()))
                .hasSize(1);

        assertThat(report(original, true)).isEqualTo(HttpStatus.NO_CONTENT);
        awaitBuildState(build.id(), BuildState.SUCCEEDED);
    }

    /**
     * Runs {@code count} complete builds of {@code taskName} so the duration estimator has a median
     * to call a later run slow relative to. Each uses its own cache key so none of them turns into
     * a cache hit that would skip execution entirely.
     */
    private void seedDurationHistory(long projectId, String taskName, int count) {
        long worker = registerWorker("worker-history-" + UUID.randomUUID());
        for (int i = 0; i < count; i++) {
            BuildResponse build = createBuild(projectId, taskName);
            ClaimedTaskResponse task = claimMine(worker, taskName);
            assertThat(report(task, true)).isEqualTo(HttpStatus.NO_CONTENT);
            awaitBuildState(build.id(), BuildState.SUCCEEDED);
        }
    }

    /**
     * Polls until {@code workerId} is handed an attempt on {@code taskRunId} that belongs to some
     * other worker — i.e. until speculation fires. Heartbeats the straggler too, so it stays alive
     * and this stays a slowdown scenario rather than a crash-recovery one.
     */
    private ClaimedTaskResponse claimSameTaskRun(
            long workerId, Long taskRunId, long stragglerWorkerId) {
        for (int i = 0; i < 200; i++) {
            heartbeat(stragglerWorkerId);
            heartbeat(workerId);
            Optional<ClaimedTaskResponse> claimed = claim(workerId);
            if (claimed.isPresent()) {
                assertThat(claimed.get().taskRunId()).isEqualTo(taskRunId);
                return claimed.get();
            }
            sleepQuietly(100);
        }
        throw new AssertionError("worker " + workerId + " was never given a speculative attempt");
    }

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
            report(task, true);
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

    private HttpStatus report(ClaimedTaskResponse task, boolean success) {
        ResponseEntity<Void> response =
                rest.postForEntity(
                        "/api/task-runs/" + task.taskRunId() + "/result",
                        new TaskResultReportRequest(
                                task.workerId(),
                                task.leaseToken(),
                                task.attemptId(),
                                success,
                                success ? 0 : 1,
                                success ? null : "failed",
                                null),
                        Void.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    private HeartbeatResponse heartbeat(long workerId) {
        return rest.postForObject(
                "/api/workers/" + workerId + "/heartbeat", null, HeartbeatResponse.class);
    }

    private long registerProject() {
        return rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class)
                .id();
    }

    private BuildResponse createBuild(long projectId, String taskName) {
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + projectId + "/plans",
                        TestFixtures.singleTaskPlan(
                                "rev-" + UUID.randomUUID(),
                                "rev-0",
                                taskName,
                                "sha256:spec-" + UUID.randomUUID()),
                        PlanSubmissionResponse.class);
        return createBuild(projectId, plan.id());
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

    private long registerWorker(String externalId) {
        WorkerRegistrationResponse response =
                rest.postForObject(
                        "/api/workers/register",
                        new WorkerRegistrationRequest(externalId, List.of("linux"), 1, "test"),
                        WorkerRegistrationResponse.class);
        return response.workerId();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
