package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.controlplane.domain.BuildEvent;
import dev.forgeci.controlplane.domain.BuildEventType;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.repository.BuildEventRepository;
import dev.forgeci.controlplane.repository.TaskAttemptRepository;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.ProtocolTestClient;
import dev.forgeci.controlplane.support.TestFixtures;
import dev.forgeci.protocol.ClaimedTaskResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

/**
 * The "no accepted task result transitions twice" property: whatever storm of duplicated,
 * misattributed, and contradictory result reports a task run is subjected to, exactly one of them
 * is ever accepted, and nothing after it can change the outcome, the completion timestamp, or the
 * event history. Each seed generates a different report sequence — duplicates of the real lease,
 * forged lease tokens, wrong attempt numbers, wrong worker ids, and conflicting success/failure
 * claims — all replayed against one real build over HTTP.
 */
class ResultIdempotencePropertyTest extends ControlPlaneIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private TaskRunRepository taskRunRepository;
    @Autowired private TaskAttemptRepository taskAttemptRepository;
    @Autowired private BuildEventRepository buildEventRepository;

    private static LongStream seeds() {
        return LongStream.rangeClosed(1, 8);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void noAcceptedTaskResultIsEverAppliedTwice(long seed) {
        ProtocolTestClient client = new ProtocolTestClient(rest);
        long projectId = client.registerProject();
        String taskName = "idempotence:build";
        String cacheKey = "sha256:idempotence-" + UUID.randomUUID();
        Long planId =
                client.submitPlan(
                                projectId,
                                TestFixtures.singleTaskPlan(
                                        "rev-" + UUID.randomUUID(), "rev-0", taskName, cacheKey))
                        .id();
        Long buildId = client.createBuild(projectId, planId).id();

        long workerId = client.registerWorker("worker-idempotence-" + UUID.randomUUID());
        ClaimedTaskResponse task = client.claimNamed(workerId, taskName);
        String digest =
                client.uploadArtifact(
                        projectId,
                        cacheKey,
                        ("output for seed " + seed)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<HttpStatus> accepted = new ArrayList<>();
        for (ClaimedTaskResponse report : reportSequence(task, seed)) {
            boolean success =
                    report.attemptId() == task.attemptId()
                            && report.leaseToken().equals(task.leaseToken());
            HttpStatus status =
                    client.reportResultStatus(
                            report,
                            success,
                            success ? 0 : 1,
                            success ? null : "forged failure",
                            digest);
            if (status == HttpStatus.NO_CONTENT) {
                accepted.add(status);
            }
        }

        assertThat(accepted).as("at least one genuine report must be accepted").isNotEmpty();

        TaskRun taskRun = taskRunRepository.findById(task.taskRunId()).orElseThrow();
        assertThat(taskRun.getState()).isEqualTo(TaskRunState.SUCCEEDED);
        assertThat(taskRun.getArtifactDigest()).isEqualTo(digest);
        assertThat(taskRun.getAttemptCount()).isEqualTo(1);
        assertThat(taskAttemptRepository.findByTaskRunIdOrderByAttemptNumber(task.taskRunId()))
                .hasSize(1);

        // one terminal transition means one terminal event, however many reports arrived
        List<BuildEvent> terminalEvents =
                buildEventRepository.findByBuildIdOrderBySequenceNumberAsc(buildId).stream()
                        .filter(
                                event ->
                                        event.getEventType() == BuildEventType.TASK_RUN_SUCCEEDED
                                                || event.getEventType()
                                                        == BuildEventType.TASK_RUN_FAILED)
                        .toList();
        assertThat(terminalEvents).hasSize(1);

        Instant completedAt = taskRun.getCompletedAt();
        client.reportResultStatus(task, true, 0, null, digest);
        assertThat(taskRunRepository.findById(task.taskRunId()).orElseThrow().getCompletedAt())
                .isEqualTo(completedAt);
    }

    /**
     * A generated mix of the genuine report and near-misses of it. The genuine one is always in the
     * sequence but never first, so acceptance has to survive being preceded by rejected impostors.
     */
    private static List<ClaimedTaskResponse> reportSequence(
            ClaimedTaskResponse genuine, long seed) {
        Random random = new Random(seed);
        List<ClaimedTaskResponse> reports = new ArrayList<>();
        for (int i = 0; i < 1 + random.nextInt(3); i++) {
            reports.add(impostor(genuine, random));
        }
        reports.add(genuine);
        for (int i = 0; i < 1 + random.nextInt(4); i++) {
            reports.add(random.nextBoolean() ? genuine : impostor(genuine, random));
        }
        return reports;
    }

    private static ClaimedTaskResponse impostor(ClaimedTaskResponse genuine, Random random) {
        return switch (random.nextInt(3)) {
            case 0 ->
                    withLease(
                            genuine,
                            genuine.workerId(),
                            UUID.randomUUID().toString(),
                            genuine.attemptId());
            case 1 ->
                    withLease(
                            genuine,
                            genuine.workerId(),
                            genuine.leaseToken(),
                            genuine.attemptId() + 1 + random.nextInt(3));
            default ->
                    withLease(
                            genuine,
                            genuine.workerId() + 1 + random.nextInt(100),
                            genuine.leaseToken(),
                            genuine.attemptId());
        };
    }

    private static ClaimedTaskResponse withLease(
            ClaimedTaskResponse task, long workerId, String leaseToken, int attemptId) {
        return new ClaimedTaskResponse(
                task.taskRunId(),
                task.buildId(),
                task.projectId(),
                task.taskName(),
                task.cacheKey(),
                task.command(),
                task.outputs(),
                task.environment(),
                task.timeoutSeconds(),
                attemptId,
                workerId,
                leaseToken);
    }
}
