package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.api.dto.PlanSubmissionResponse;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.ProtocolTestClient;
import dev.forgeci.controlplane.support.TestFixtures;
import dev.forgeci.protocol.ClaimedTaskResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Proves GET /api/builds/{id}/events streams the real, ordered event log and closes once the build
 * is done.
 */
class BuildEventsIntegrationTest extends ControlPlaneIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private ProtocolTestClient client;

    @BeforeEach
    void setUp() {
        client = new ProtocolTestClient(rest);
    }

    @Test
    void streamsEventsForACompletedBuildThenCloses() throws Exception {
        long projectId = client.registerProject();
        String cacheKey = "sha256:events-" + UUID.randomUUID();
        PlanSubmissionResponse plan =
                client.submitPlan(
                        projectId,
                        TestFixtures.singleTaskPlan(
                                "rev-events-1", "rev-0", "events:build", cacheKey));
        BuildResponse build = client.createBuild(projectId, plan.id());

        long workerId = client.registerWorker("worker-events-" + UUID.randomUUID());
        ClaimedTaskResponse task = client.claimNamed(workerId, "events:build");

        byte[] archive = "events output".getBytes(StandardCharsets.UTF_8);
        String digest = client.uploadArtifact(projectId, cacheKey, archive);
        client.reportResult(task, true, 0, null, digest);

        // the emitter below only closes once the build is terminal, so failing here instead names
        // the actual problem rather than surfacing it as an opaque stream timeout
        client.awaitBuildState(build.id(), BuildState.SUCCEEDED);

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/api/builds/"
                                                + build.id()
                                                + "/events"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
        // the request builder's own .timeout() bounds connection/header time, but does not reliably
        // bound how long collecting a streamed (chunked, SSE) body via BodyHandlers.ofString() can
        // take if the server-side emitter is slow to close — sendAsync().get(timeout) enforces a
        // hard wall-clock bound on the whole exchange regardless, so a stuck emitter fails this
        // test in 15s instead of hanging indefinitely
        HttpResponse<String> response =
                httpClient
                        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .get(15, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("build-event");
        assertThat(response.body()).contains("\"eventType\"");
    }
}
