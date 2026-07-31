package dev.forgeci.worker;

import dev.forgeci.cache.Digests;
import dev.forgeci.cache.HttpRemoteArtifactClient;
import dev.forgeci.cache.TaskArchive;
import dev.forgeci.protocol.ClaimedTaskResponse;
import dev.forgeci.protocol.LogChunkRequest;
import dev.forgeci.protocol.TaskResultReportRequest;
import dev.forgeci.protocol.WorkerRegistrationRequest;
import dev.forgeci.protocol.WorkerRegistrationResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * The worker process entry point: registers, heartbeats on a fixed interval, and loops
 * claim-execute-report against the control plane with no Kafka involved — the direct HTTP path
 * phase 5 proves before Kafka is layered on top.
 */
public final class WorkerMain {

    private static final System.Logger log = System.getLogger("forge.worker");

    private WorkerMain() {}

    public static void main(String[] args) throws InterruptedException {
        WorkerConfig config = WorkerConfig.fromEnvironment();
        seedWorkspaceIfEmpty(config);

        ControlPlaneClient controlPlane = new ControlPlaneClient(config.controlPlaneUrl());
        HttpRemoteArtifactClient artifacts = new HttpRemoteArtifactClient(config.controlPlaneUrl());
        DockerTaskExecutor executor = new DockerTaskExecutor(config);

        WorkerRegistrationResponse registration =
                registerWithRetry(controlPlane, config, Duration.ofSeconds(2), 30);
        long workerId = registration.workerId();
        log.log(System.Logger.Level.INFO, "registered as worker {0} ({1})", workerId, config.externalId());

        AtomicBoolean running = new AtomicBoolean(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> running.set(false), "forge-worker-shutdown"));

        ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "forge-worker-heartbeat"));
        heartbeats.scheduleWithFixedDelay(
                () -> safely(() -> controlPlane.heartbeat(workerId)),
                registration.heartbeatIntervalMs(),
                registration.heartbeatIntervalMs(),
                TimeUnit.MILLISECONDS);

        try {
            while (running.get()) {
                Optional<ClaimedTaskResponse> claimed = safelyClaim(controlPlane, workerId);
                if (claimed.isEmpty()) {
                    Thread.sleep(config.pollIntervalMs());
                    continue;
                }
                executeAndReport(claimed.get(), controlPlane, artifacts, executor, config);
            }
        } finally {
            heartbeats.shutdownNow();
        }
    }

    private static void executeAndReport(
            ClaimedTaskResponse task,
            ControlPlaneClient controlPlane,
            HttpRemoteArtifactClient artifacts,
            DockerTaskExecutor executor,
            WorkerConfig config) {
        log.log(System.Logger.Level.INFO, "claimed task {0} (run {1}, attempt {2})", task.taskName(), task.taskRunId(), task.attemptId());

        DockerTaskExecutor.ExecutionResult result =
                executor.run(
                        task,
                        lines ->
                                safely(
                                        () ->
                                                controlPlane.appendLogs(
                                                        task.taskRunId(), new LogChunkRequest(task.workerId(), task.leaseToken(), task.attemptId(), lines))));

        String artifactDigest = null;
        boolean success = result.success();
        String failureReason = result.failureReason();
        if (success) {
            try {
                byte[] archive = TaskArchive.write(config.workspaceRoot(), task.outputs());
                artifactDigest = Digests.sha256(archive);
                artifacts.upload(task.projectId(), task.cacheKey(), archive);
            } catch (RuntimeException archiveFailure) {
                success = false;
                failureReason = "output archiving/upload failed: " + archiveFailure.getMessage();
            }
        }

        TaskResultReportRequest report =
                new TaskResultReportRequest(task.workerId(), task.leaseToken(), task.attemptId(), success, result.exitCode(), failureReason, artifactDigest);
        safely(() -> controlPlane.reportResult(task.taskRunId(), report));
    }

    private static WorkerRegistrationResponse registerWithRetry(
            ControlPlaneClient controlPlane, WorkerConfig config, Duration retryDelay, int maxAttempts) throws InterruptedException {
        WorkerRegistrationRequest request =
                new WorkerRegistrationRequest(config.externalId(), config.capabilities(), config.maxConcurrency(), config.versionLabel());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return controlPlane.register(request);
            } catch (ControlPlaneUnavailableException e) {
                log.log(System.Logger.Level.WARNING, "registration attempt {0}/{1} failed: {2}", attempt, maxAttempts, e.getMessage());
                Thread.sleep(retryDelay.toMillis());
            }
        }
        throw new IllegalStateException("could not register with control plane after " + maxAttempts + " attempts");
    }

    private static Optional<ClaimedTaskResponse> safelyClaim(ControlPlaneClient controlPlane, long workerId) {
        try {
            return controlPlane.claim(workerId);
        } catch (ControlPlaneUnavailableException e) {
            log.log(System.Logger.Level.WARNING, "claim failed: {0}", e.getMessage());
            return Optional.empty();
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (ControlPlaneUnavailableException e) {
            log.log(System.Logger.Level.WARNING, "control plane call failed: {0}", e.getMessage());
        }
    }

    /**
     * A fresh named volume mounted at the workspace root is empty on first boot — seed it once from
     * the image's bundled copy. Every worker shares that one volume and boots at the same time, so
     * plain "is it empty?" would let two of them copy concurrently and the loser would die on an
     * already-created file. Directory creation is atomic on POSIX: whoever creates the lock owns the
     * seeding, and the others block on the completion marker so nobody runs against a half-copy.
     */
    private static void seedWorkspaceIfEmpty(WorkerConfig config) {
        if (config.seedWorkspaceFrom() == null) {
            return;
        }
        Path root = config.workspaceRoot();
        Path seeded = root.resolve(".forge-seeded");
        Path lock = root.resolve(".forge-seeding");
        try {
            Files.createDirectories(root);
            if (Files.exists(seeded)) {
                return;
            }
            try {
                Files.createDirectory(lock);
            } catch (FileAlreadyExistsException seedingElsewhere) {
                awaitSeedCompletion(seeded);
                return;
            }
            if (isEmptyApartFromLock(root, lock)) {
                log.log(System.Logger.Level.INFO, "seeding empty workspace {0} from {1}", root, config.seedWorkspaceFrom());
                copyRecursively(config.seedWorkspaceFrom(), root);
            }
            Files.createFile(seeded);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to seed workspace from " + config.seedWorkspaceFrom(), e);
        }
    }

    private static boolean isEmptyApartFromLock(Path root, Path lock) throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.noneMatch(entry -> !entry.equals(lock));
        }
    }

    /** Bounded so a worker whose peer died mid-seed fails with a clear error instead of hanging forever. */
    private static void awaitSeedCompletion(Path seeded) throws IOException {
        for (int attempt = 0; attempt < 120; attempt++) {
            if (Files.exists(seeded)) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for another worker to seed the workspace", e);
            }
        }
        throw new IOException("timed out waiting for another worker to finish seeding the workspace");
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
