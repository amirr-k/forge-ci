package dev.forgeci.cache;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the control plane's {@code /api/projects} and {@code /api/artifacts} endpoints over
 * plain HTTP. Deliberately dependency-free JSON handling (a single-field regex extraction) rather
 * than pulling in a JSON library for one integer field — the request/response shapes here are small
 * and fixed by this same phase.
 */
public final class HttpRemoteArtifactClient implements RemoteArtifactClient {

    private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private final URI baseUri;
    private final HttpClient client;
    private final Duration timeout;

    public HttpRemoteArtifactClient(URI baseUri) {
        this(baseUri, Duration.ofSeconds(30));
    }

    public HttpRemoteArtifactClient(URI baseUri, Duration timeout) {
        this.baseUri = baseUri;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public long ensureProject(
            String name, String repositoryIdentity, String defaultBranch, int configVersion) {
        String body =
                "{\"name\":\"%s\",\"repositoryIdentity\":\"%s\",\"defaultBranch\":\"%s\",\"configVersion\":%d}"
                        .formatted(
                                jsonEscape(name),
                                jsonEscape(repositoryIdentity),
                                jsonEscape(defaultBranch),
                                configVersion);
        HttpRequest request =
                requestBuilder("/api/projects")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new RemoteCacheUnavailableException(
                    "project registration failed: HTTP " + response.statusCode());
        }
        Matcher matcher = ID_FIELD.matcher(response.body());
        if (!matcher.find()) {
            throw new RemoteCacheUnavailableException(
                    "project registration response had no id: " + response.body());
        }
        return Long.parseLong(matcher.group(1));
    }

    @Override
    public Optional<byte[]> lookup(long projectId, String cacheKey) {
        String path =
                "/api/artifacts/lookup?projectId=" + projectId + "&cacheKey=" + encode(cacheKey);
        HttpRequest request = requestBuilder(path).GET().build();
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new RemoteCacheUnavailableException(
                    "artifact lookup failed: HTTP " + response.statusCode());
        }
        byte[] content = response.body();
        String declaredDigest = response.headers().firstValue("X-Artifact-Digest").orElse(null);
        if (declaredDigest == null) {
            throw new CorruptArtifactException(
                    "remote artifact response for " + cacheKey + " was missing its digest header");
        }
        String actualDigest = Digests.sha256(content);
        if (!actualDigest.equals(declaredDigest)) {
            throw new CorruptArtifactException(
                    "remote artifact for "
                            + cacheKey
                            + " does not match its declared digest "
                            + declaredDigest
                            + " (got "
                            + actualDigest
                            + ")");
        }
        return Optional.of(content);
    }

    @Override
    public void upload(long projectId, String cacheKey, byte[] archive) {
        String digest = Digests.sha256(archive);
        String path =
                "/api/artifacts?projectId="
                        + projectId
                        + "&cacheKey="
                        + encode(cacheKey)
                        + "&digest="
                        + digest
                        + "&size="
                        + archive.length;
        HttpRequest request =
                requestBuilder(path)
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(archive))
                        .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new RemoteCacheUnavailableException(
                    "artifact upload failed: HTTP "
                            + response.statusCode()
                            + " "
                            + response.body());
        }
    }

    private HttpRequest.Builder requestBuilder(String pathAndQuery) {
        return HttpRequest.newBuilder(baseUri.resolve(pathAndQuery)).timeout(timeout);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return client.send(request, handler);
        } catch (IOException e) {
            throw new RemoteCacheUnavailableException(
                    "could not reach control plane at " + baseUri + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteCacheUnavailableException(
                    "interrupted while calling control plane at " + baseUri);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
