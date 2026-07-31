package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.cache.Digests;
import dev.forgeci.controlplane.api.dto.BuildCreationRequest;
import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.api.dto.PlanSubmissionResponse;
import dev.forgeci.controlplane.api.dto.ProjectResponse;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.TestFixtures;
import dev.forgeci.protocol.ClaimedTaskResponse;
import dev.forgeci.protocol.TaskResultReportRequest;
import dev.forgeci.protocol.WorkerRegistrationRequest;
import dev.forgeci.protocol.WorkerRegistrationResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Proves GET /api/builds/{id}/events streams the real, ordered event log and closes once the build is done. */
class BuildEventsIntegrationTest extends ControlPlaneIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    @Test
    void streamsEventsForACompletedBuildThenCloses() throws Exception {
        ProjectResponse project = rest.postForObject("/api/projects", TestFixtures.project(), ProjectResponse.class);
        String cacheKey = "sha256:events-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                rest.postForObject(
                        "/api/projects/" + project.id() + "/plans",
                        TestFixtures.singleTaskPlan("rev-events-1", "rev-0", "events:build", cacheKey),
                        PlanSubmissionResponse.class);
        BuildResponse build =
                rest.postForObject(
                        "/api/projects/" + project.id() + "/builds",
                        new BuildCreationRequest(plan.id(), "manual", 0),
                        BuildResponse.class);

        WorkerRegistrationResponse worker =
                rest.postForObject(
                        "/api/workers/register",
                        new WorkerRegistrationRequest("worker-events", List.of(), 1, "test"),
                        WorkerRegistrationResponse.class);
        ClaimedTaskResponse task = null;
        for (int i = 0; i < 200 && task == null; i++) {
            ResponseEntity<ClaimedTaskResponse> response =
                    rest.postForEntity("/api/workers/" + worker.workerId() + "/claim", null, ClaimedTaskResponse.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                task = response.getBody();
            } else {
                Thread.sleep(25);
            }
        }
        assertThat(task).isNotNull();

        byte[] archive = "events output".getBytes();
        String digest = Digests.sha256(archive);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        rest.exchange(
                "/api/artifacts?projectId=" + project.id() + "&cacheKey=" + cacheKey + "&digest=" + digest + "&size=" + archive.length,
                HttpMethod.POST,
                new HttpEntity<>(archive, headers),
                Map.class);
        rest.postForEntity(
                "/api/task-runs/" + task.taskRunId() + "/result",
                new TaskResultReportRequest(task.workerId(), task.leaseToken(), task.attemptId(), true, 0, null, digest),
                Void.class);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/builds/" + build.id() + "/events"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("build-event");
        assertThat(response.body()).contains("\"eventType\"");
    }
}
