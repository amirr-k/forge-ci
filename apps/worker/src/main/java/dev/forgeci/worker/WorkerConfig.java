package dev.forgeci.worker;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * Everything the worker needs, read once from the environment at startup — no config file, matching
 * a Docker/Compose worker's usual shape.
 */
public record WorkerConfig(
        URI controlPlaneUrl,
        String externalId,
        List<String> capabilities,
        int maxConcurrency,
        String versionLabel,
        Path workspaceRoot,
        Path seedWorkspaceFrom,
        String dockerImage,
        String dockerCpus,
        String dockerMemory,
        String dockerVolume,
        long pollIntervalMs) {

    public static WorkerConfig fromEnvironment() {
        return new WorkerConfig(
                URI.create(require("FORGE_CONTROL_PLANE_URL")),
                env("FORGE_WORKER_ID", defaultExternalId()),
                splitCsv(env("FORGE_WORKER_CAPABILITIES", "")),
                Integer.parseInt(env("FORGE_WORKER_MAX_CONCURRENCY", "2")),
                env("FORGE_WORKER_VERSION", "0.1.0-SNAPSHOT"),
                Path.of(env("FORGE_WORKSPACE_ROOT", "/workspace")),
                env("FORGE_SEED_WORKSPACE_FROM", "").isBlank()
                        ? null
                        : Path.of(env("FORGE_SEED_WORKSPACE_FROM", "")),
                env("FORGE_WORKER_DOCKER_IMAGE", "alpine:3.20"),
                env("FORGE_WORKER_DOCKER_CPUS", "1"),
                env("FORGE_WORKER_DOCKER_MEMORY", "512m"),
                env("FORGE_WORKER_DOCKER_VOLUME", "").isBlank()
                        ? null
                        : env("FORGE_WORKER_DOCKER_VOLUME", ""),
                Long.parseLong(env("FORGE_WORKER_POLL_INTERVAL_MS", "1000")));
    }

    /**
     * What a sibling task container should mount at {@code /workspace}: the named volume if this
     * worker itself runs docker-outside-of-docker, otherwise this worker's own bind-mounted path.
     */
    public String dockerMountSource() {
        return dockerVolume != null ? dockerVolume : workspaceRoot.toString();
    }

    private static String defaultExternalId() {
        String hostname = System.getenv("HOSTNAME");
        return hostname != null && !hostname.isBlank()
                ? hostname
                : "worker-" + java.util.UUID.randomUUID();
    }

    private static List<String> splitCsv(String value) {
        return value.isBlank()
                ? List.of()
                : List.of(value.split(",")).stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }
}
