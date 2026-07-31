package dev.forgeci.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forgeci.protocol.ClaimedTaskResponse;
import dev.forgeci.protocol.HeartbeatResponse;
import dev.forgeci.protocol.LogChunkRequest;
import dev.forgeci.protocol.TaskResultReportRequest;
import dev.forgeci.protocol.WorkerRegistrationRequest;
import dev.forgeci.protocol.WorkerRegistrationResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** The worker side of the REST worker protocol fixed in spec/reference/architecture.md#worker-protocol. */
public final class ControlPlaneClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI baseUri;
    private final HttpClient client;
    private final Duration timeout;

    public ControlPlaneClient(URI baseUri) {
        this(baseUri, Duration.ofSeconds(30));
    }

    public ControlPlaneClient(URI baseUri, Duration timeout) {
        this.baseUri = baseUri;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public WorkerRegistrationResponse register(WorkerRegistrationRequest request) {
        HttpResponse<String> response = post("/api/workers/register", request);
        if (response.statusCode() != 201) {
            throw new ControlPlaneUnavailableException("worker registration failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return readValue(response.body(), WorkerRegistrationResponse.class);
    }

    public HeartbeatResponse heartbeat(long workerId) {
        HttpRequest request = requestBuilder("/api/workers/" + workerId + "/heartbeat").POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new ControlPlaneUnavailableException("heartbeat failed: HTTP " + response.statusCode());
        }
        return readValue(response.body(), HeartbeatResponse.class);
    }

    /** Empty means no claimable task run right now — not an error, the caller should poll again. */
    public Optional<ClaimedTaskResponse> claim(long workerId) {
        HttpRequest request = requestBuilder("/api/workers/" + workerId + "/claim").POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new ControlPlaneUnavailableException("claim failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return Optional.of(readValue(response.body(), ClaimedTaskResponse.class));
    }

    public void appendLogs(long taskRunId, LogChunkRequest request) {
        HttpResponse<String> response = post("/api/task-runs/" + taskRunId + "/logs", request);
        if (response.statusCode() != 204) {
            throw new ControlPlaneUnavailableException("log append failed: HTTP " + response.statusCode() + " " + response.body());
        }
    }

    public void reportResult(long taskRunId, TaskResultReportRequest request) {
        HttpResponse<String> response = post("/api/task-runs/" + taskRunId + "/result", request);
        if (response.statusCode() != 204) {
            throw new ControlPlaneUnavailableException("result report failed: HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private HttpResponse<String> post(String path, Object body) {
        HttpRequest request =
                requestBuilder(path)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(writeValue(body), StandardCharsets.UTF_8))
                        .build();
        return send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder requestBuilder(String pathAndQuery) {
        return HttpRequest.newBuilder(baseUri.resolve(pathAndQuery)).timeout(timeout);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return client.send(request, handler);
        } catch (IOException e) {
            throw new ControlPlaneUnavailableException("could not reach control plane at " + baseUri + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlPlaneUnavailableException("interrupted while calling control plane at " + baseUri);
        }
    }

    private static String writeValue(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> T readValue(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
