package dev.forgeci.controlplane.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.cache.Digests;
import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.api.dto.PlanSubmissionRequest;
import dev.forgeci.controlplane.api.dto.PlanSubmissionResponse;
import dev.forgeci.controlplane.api.dto.ProjectResponse;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.protocol.ClaimedTaskResponse;
import dev.forgeci.protocol.HeartbeatResponse;
import dev.forgeci.protocol.TaskResultReportRequest;
import dev.forgeci.protocol.WorkerRegistrationRequest;
import dev.forgeci.protocol.WorkerRegistrationResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The worker/CLI side of the HTTP protocol, driven exactly as a real client would drive it — no
 * service beans, no shared JVM state, so a test using this proves the wire contract and not just
 * the Java call. Shared by the property, concurrency, and security suites.
 */
public final class ProtocolTestClient {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private final TestRestTemplate rest;

    public ProtocolTestClient(TestRestTemplate rest) {
        this.rest = rest;
    }

    public long registerProject() {
        return rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class)
                .id();
    }

    public PlanSubmissionResponse submitPlan(long projectId, PlanSubmissionRequest plan) {
        return rest.postForObject(
                "/api/projects/" + projectId + "/plans", plan, PlanSubmissionResponse.class);
    }

    public BuildResponse createBuild(long projectId, Long planSubmissionId) {
        return rest.postForObject(
                "/api/projects/" + projectId + "/builds",
                new BuildCreationRequest(planSubmissionId, "manual", 0),
                BuildResponse.class);
    }

    public BuildResponse getBuild(Long buildId) {
        return rest.getForObject("/api/builds/" + buildId, BuildResponse.class);
    }

    public long registerWorker(String externalId) {
        return registerWorker(externalId, 1);
    }

    public long registerWorker(String externalId, int maxConcurrency) {
        return rest.postForObject(
                        "/api/workers/register",
                        new WorkerRegistrationRequest(
                                externalId, List.of(), maxConcurrency, "test"),
                        WorkerRegistrationResponse.class)
                .workerId();
    }

    public HeartbeatResponse heartbeat(long workerId) {
        return rest.postForObject(
                "/api/workers/" + workerId + "/heartbeat", null, HeartbeatResponse.class);
    }

    public Optional<ClaimedTaskResponse> claim(long workerId) {
        ResponseEntity<ClaimedTaskResponse> response =
                rest.postForEntity(
                        "/api/workers/" + workerId + "/claim", null, ClaimedTaskResponse.class);
        if (response.getStatusCode() == HttpStatus.NO_CONTENT) {
            return Optional.empty();
        }
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Optional.ofNullable(response.getBody());
    }

    /** Polls (heartbeating like a live worker would) until this worker claims {@code taskName}. */
    public ClaimedTaskResponse claimNamed(long workerId, String taskName) {
        return claimOneOf(workerId, Set.of(taskName));
    }

    /**
     * Polls until this worker claims one of {@code taskNames}. Two things here are what make a
     * claim-polling test survive running alongside the rest of the suite, and neither is optional.
     *
     * <p>Heartbeating on every poll: the integration profile marks a worker unhealthy after three
     * missed intervals — three seconds — and an unhealthy worker is excluded from claims until it
     * heartbeats again, so a silently polling worker starves itself no matter how long it waits.
     *
     * <p>Completing foreign claims rather than keeping them: the claim queue is global across every
     * build in the system, so a poll can hand back another test class's task run. Holding one
     * strands its build; returning it as if it were ours strands ours.
     */
    public ClaimedTaskResponse claimOneOf(long workerId, Set<String> taskNames) {
        for (int i = 0; i < 400; i++) {
            heartbeat(workerId);
            Optional<ClaimedTaskResponse> claimed = claim(workerId);
            if (claimed.isEmpty()) {
                sleepQuietly(100);
                continue;
            }
            if (taskNames.contains(claimed.get().taskName())) {
                return claimed.get();
            }
            drain(claimed.get());
        }
        throw new AssertionError("worker " + workerId + " never claimed one of " + taskNames);
    }

    /**
     * Harmlessly completes a foreign backlog task. The report is allowed to be rejected — a
     * leftover whose lease expired between the claim and this call is already being reclaimed by
     * the lease sweep, which releases the worker's slot either way.
     */
    public void drain(ClaimedTaskResponse foreign) {
        reportResultStatus(foreign, true, 0, null, null);
    }

    public void reportResult(
            ClaimedTaskResponse task,
            boolean success,
            Integer exitCode,
            String failureReason,
            String artifactDigest) {
        assertThat(reportResultStatus(task, success, exitCode, failureReason, artifactDigest))
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** The raw status, for callers that expect a report to be rejected rather than accepted. */
    public HttpStatus reportResultStatus(
            ClaimedTaskResponse task,
            boolean success,
            Integer exitCode,
            String failureReason,
            String artifactDigest) {
        ResponseEntity<String> response =
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
                        String.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    public String uploadArtifact(long projectId, String cacheKey, byte[] archive) {
        ResponseEntity<Map<String, Object>> response =
                upload(projectId, cacheKey, archive, Digests.sha256(archive), archive.length);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Digests.sha256(archive);
    }

    /**
     * The upload call with every field left to the caller, so a test can declare a wrong digest or
     * size on purpose.
     */
    public ResponseEntity<Map<String, Object>> upload(
            long projectId,
            String cacheKey,
            byte[] archive,
            String declaredDigest,
            long declaredSize) {
        String path =
                "/api/artifacts?projectId="
                        + projectId
                        + "&cacheKey="
                        + encode(cacheKey)
                        + "&digest="
                        + declaredDigest
                        + "&size="
                        + declaredSize;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return rest.exchange(
                path, HttpMethod.POST, new HttpEntity<>(archive, headers), JSON_OBJECT);
    }

    public ResponseEntity<byte[]> lookup(long projectId, String cacheKey) {
        return rest.getForEntity(
                "/api/artifacts/lookup?projectId=" + projectId + "&cacheKey=" + encode(cacheKey),
                byte[].class);
    }

    public void awaitBuildState(Long buildId, BuildState expected) {
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

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
