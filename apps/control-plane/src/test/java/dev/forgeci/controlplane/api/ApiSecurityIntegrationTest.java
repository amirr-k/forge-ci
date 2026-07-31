package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.cache.Digests;
import dev.forgeci.controlplane.api.dto.PlanSubmissionRequest;
import dev.forgeci.controlplane.api.dto.TaskDefinitionRequest;
import dev.forgeci.controlplane.config.S3Properties;
import dev.forgeci.controlplane.domain.TaskDefinitionEntity;
import dev.forgeci.controlplane.repository.PlanSubmissionRepository;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.ProtocolTestClient;
import dev.forgeci.controlplane.support.TestFixtures;
import dev.forgeci.protocol.ClaimedTaskResponse;
import dev.forgeci.protocol.LogChunkRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

/**
 * The authenticated-surface half of the security taxonomy: a hostile cache key can never steer
 * where bytes land in object storage, a task's command is stored and handed out as the argv array
 * it was submitted as (never a shell string to be re-split), and no caller can act on a lease it
 * does not hold.
 */
class ApiSecurityIntegrationTest extends ControlPlaneIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    @Autowired private TestRestTemplate rest;
    @Autowired private S3Properties s3Properties;
    @Autowired private PlanSubmissionRepository planSubmissionRepository;

    private ProtocolTestClient client;

    @BeforeEach
    void createClient() {
        client = new ProtocolTestClient(rest);
    }

    @Test
    void aCacheKeyThatLooksLikeAPathNeverDecidesWhereTheObjectIsStored() {
        long projectId = client.registerProject();
        byte[] archive = "bytes for a hostile key".getBytes(StandardCharsets.UTF_8);
        String digest = Digests.sha256(archive);

        for (String hostileKey :
                List.of(
                        "../../../../etc/passwd",
                        "..%2f..%2fetc%2fshadow",
                        "/absolute/key",
                        "tmp/" + UUID.randomUUID(),
                        "artifacts/00/deadbeef")) {
            ResponseEntity<Map<String, Object>> response =
                    client.upload(projectId, hostileKey, archive, digest, archive.length);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String objectStoreKey = (String) response.getBody().get("objectStoreKey");
            // the key is content-addressed from the digest alone; the caller's string never reaches
            // it
            assertThat(objectStoreKey).isEqualTo(s3Properties.objectKey(digest));
            assertThat(objectStoreKey).startsWith(s3Properties.getObjectPrefix());
            assertThat(objectStoreKey).doesNotContain("..");
        }
    }

    @Test
    void aLookupForACacheKeyThisProjectNeverUploadedIsANotFoundNotAReadOfSomeoneElsesObject() {
        long owner = client.registerProject();
        long stranger = client.registerProject();
        String cacheKey = "sha256:private-" + UUID.randomUUID();
        client.uploadArtifact(owner, cacheKey, "owner's output".getBytes(StandardCharsets.UTF_8));

        assertThat(client.lookup(stranger, cacheKey).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.lookup(owner, "../" + cacheKey).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Transactional(readOnly = true)
    void aSubmittedCommandIsStoredAsTheExactArgvArrayItArrivedAs() {
        long projectId = client.registerProject();
        List<String> argv =
                List.of("/bin/echo", "$(id) && rm -rf / ; echo 'quoted arg'", "--flag=a b c");
        PlanSubmissionRequest plan =
                new PlanSubmissionRequest(
                        "rev-" + UUID.randomUUID(),
                        "rev-0",
                        false,
                        List.of("services/argv/src/A.java"),
                        List.of(
                                new TaskDefinitionRequest(
                                        "argv:build",
                                        List.of(),
                                        "sha256:argv-" + UUID.randomUUID(),
                                        "source changed",
                                        argv,
                                        List.of(),
                                        List.of(),
                                        60)),
                        List.of());
        Long planId = client.submitPlan(projectId, plan).id();

        List<TaskDefinitionEntity> stored =
                planSubmissionRepository.findById(planId).orElseThrow().getTasks();

        assertThat(stored).hasSize(1);
        // no splitting on spaces, no shell quoting round-trip: the array survives verbatim
        assertThat(stored.get(0).getCommand()).isEqualTo(argv);
    }

    @Test
    void aTaskWithNoCommandIsRejectedRatherThanScheduledAsANoOp() {
        long projectId = client.registerProject();
        PlanSubmissionRequest plan =
                new PlanSubmissionRequest(
                        "rev-" + UUID.randomUUID(),
                        "rev-0",
                        false,
                        List.of("services/empty/src/A.java"),
                        List.of(
                                new TaskDefinitionRequest(
                                        "empty:build",
                                        List.of(),
                                        "sha256:empty",
                                        "source changed",
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        60)),
                        List.of());

        ResponseEntity<Map<String, Object>> response =
                rest.exchange(
                        "/api/projects/" + projectId + "/plans",
                        HttpMethod.POST,
                        new HttpEntity<>(plan),
                        JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "validation_failed");
    }

    @Test
    void aWorkerCannotReportResultsOrLogsForALeaseItDoesNotHold() {
        long projectId = client.registerProject();
        String cacheKey = "sha256:lease-auth-" + UUID.randomUUID();
        Long planId =
                client.submitPlan(
                                projectId,
                                TestFixtures.singleTaskPlan(
                                        "rev-" + UUID.randomUUID(),
                                        "rev-0",
                                        "leased:build",
                                        cacheKey))
                        .id();
        client.createBuild(projectId, planId);

        long holder = client.registerWorker("worker-holder-" + UUID.randomUUID());
        long intruder = client.registerWorker("worker-intruder-" + UUID.randomUUID());
        ClaimedTaskResponse task = client.claimNamed(holder, "leased:build");

        ResponseEntity<Map<String, Object>> logs =
                rest.exchange(
                        "/api/task-runs/" + task.taskRunId() + "/logs",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new LogChunkRequest(
                                        intruder,
                                        UUID.randomUUID().toString(),
                                        task.attemptId(),
                                        List.of("injected log line"))),
                        JSON_OBJECT);
        assertThat(logs.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ClaimedTaskResponse forged =
                new ClaimedTaskResponse(
                        task.taskRunId(),
                        task.buildId(),
                        task.projectId(),
                        task.taskName(),
                        task.cacheKey(),
                        task.command(),
                        task.outputs(),
                        task.environment(),
                        task.timeoutSeconds(),
                        task.attemptId(),
                        intruder,
                        UUID.randomUUID().toString());
        assertThat(client.reportResultStatus(forged, true, 0, null, null))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
