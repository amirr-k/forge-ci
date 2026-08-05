package dev.forgeci.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.forgeci.cache.Digests;
import dev.forgeci.controlplane.api.dto.BuildResponse;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.repository.ArtifactRepository;
import dev.forgeci.controlplane.repository.TaskAttemptRepository;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import dev.forgeci.controlplane.support.ControlPlaneIntegrationTest;
import dev.forgeci.controlplane.support.ProtocolTestClient;
import dev.forgeci.controlplane.support.TestFixtures;
import dev.forgeci.protocol.ClaimedTaskResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The concurrency scenarios the taxonomy names, driven over HTTP the way independent processes
 * would: two builds in flight at once, two workers pulling from the one global queue, a worker
 * reporting into its own lease expiry, and several clients committing the same artifact bytes
 * simultaneously. Every assertion is on an invariant that must hold whichever side wins the race —
 * never on which side wins.
 */
class ConcurrencyIntegrationTest extends ControlPlaneIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private TaskRunRepository taskRunRepository;
    @Autowired private TaskAttemptRepository taskAttemptRepository;
    @Autowired private ArtifactRepository artifactRepository;

    private ProtocolTestClient client;

    @BeforeEach
    void createClient() {
        client = new ProtocolTestClient(rest);
    }

    @Test
    void twoBuildsInFlightAtOnceBothCompleteAndNoTaskRunIsExecutedTwice() throws Exception {
        long projectId = client.registerProject();
        BuildResponse first = submitTwoIndependentTaskBuild(projectId);
        BuildResponse second = submitTwoIndependentTaskBuild(projectId);

        Set<Long> ourTaskRuns = new HashSet<>();
        taskRunRepository.findByBuildId(first.id()).forEach(run -> ourTaskRuns.add(run.getId()));
        taskRunRepository.findByBuildId(second.id()).forEach(run -> ourTaskRuns.add(run.getId()));
        assertThat(ourTaskRuns).hasSize(4);

        ConcurrentLinkedQueue<Long> claimed = new ConcurrentLinkedQueue<>();
        runWorkersUntil(2, () -> claimed.size() >= 4, claimed::add);

        client.awaitBuildState(first.id(), BuildState.SUCCEEDED);
        client.awaitBuildState(second.id(), BuildState.SUCCEEDED);

        List<Long> ourClaims = claimed.stream().filter(ourTaskRuns::contains).toList();
        assertThat(ourClaims)
                .as("a task run claimed twice would mean two workers executing it")
                .doesNotHaveDuplicates();
        for (Long taskRunId : ourTaskRuns) {
            assertThat(taskAttemptRepository.findByTaskRunIdOrderByAttemptNumber(taskRunId))
                    .hasSize(1);
        }
    }

    /**
     * The last two tasks of a build finishing at the same instant on two workers, which is the
     * moment a build's completion check is most exposed: each report commits in its own READ
     * COMMITTED transaction, and a check that missed the other's task would leave every task
     * SUCCEEDED and the build stuck RUNNING. The invariant is simply that the build still
     * completes, whichever report commits first.
     */
    @Test
    void aBuildWhoseLastTwoTasksFinishSimultaneouslyStillCompletes() throws Exception {
        long projectId = client.registerProject();

        // repeated because this is a commit-interleaving race: one pass can win by luck
        for (int round = 0; round < 8; round++) {
            String suffix = UUID.randomUUID().toString();
            Long planId =
                    client.submitPlan(
                                    projectId,
                                    TestFixtures.twoIndependentTaskPlan(
                                            "rev-simultaneous-" + suffix, "rev-0"))
                            .id();
            BuildResponse build = client.createBuild(projectId, planId);

            long worker1 = client.registerWorker("worker-sim-1-" + suffix);
            long worker2 = client.registerWorker("worker-sim-2-" + suffix);
            Set<String> mine = Set.of("alpha:build", "beta:build");
            ClaimedTaskResponse a = client.claimOneOf(worker1, mine);
            ClaimedTaskResponse b = client.claimOneOf(worker2, mine);
            assertThat(a.taskRunId()).isNotEqualTo(b.taskRunId());

            CountDownLatch bothReady = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var f1 = pool.submit(() -> reportOnRelease(bothReady, a));
                var f2 = pool.submit(() -> reportOnRelease(bothReady, b));
                bothReady.countDown();
                f1.get(60, TimeUnit.SECONDS);
                f2.get(60, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            client.awaitBuildState(build.id(), BuildState.SUCCEEDED);
        }
    }

    private Void reportOnRelease(CountDownLatch releaseAllAtOnce, ClaimedTaskResponse task)
            throws Exception {
        releaseAllAtOnce.await();
        client.reportResult(task, true, 0, null, null);
        return null;
    }

    @Test
    void severalClientsCommittingTheSameBytesAtOnceProduceExactlyOneArtifact() throws Exception {
        long projectId = client.registerProject();
        byte[] archive =
                ("concurrently uploaded " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String digest = Digests.sha256(archive);
        int uploaders = 6;

        CountDownLatch releaseAllAtOnce = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(uploaders);
        List<java.util.concurrent.Future<HttpStatus>> results = new ArrayList<>();
        try {
            for (int i = 0; i < uploaders; i++) {
                String cacheKey = "sha256:concurrent-" + i + "-" + UUID.randomUUID();
                results.add(
                        pool.submit(
                                () -> {
                                    releaseAllAtOnce.await();
                                    ResponseEntity<Map<String, Object>> response =
                                            client.upload(
                                                    projectId,
                                                    cacheKey,
                                                    archive,
                                                    digest,
                                                    archive.length);
                                    return HttpStatus.valueOf(response.getStatusCode().value());
                                }));
            }
            releaseAllAtOnce.countDown();
            for (java.util.concurrent.Future<HttpStatus> result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(HttpStatus.CREATED);
            }
        } finally {
            pool.shutdownNow();
        }

        // content addressing means one object and one row, no matter how many callers raced to
        // write it
        assertThat(artifactRepository.findByDigest(digest)).isPresent();
        assertThat(
                        artifactRepository.findAll().stream()
                                .filter(artifact -> artifact.getDigest().equals(digest)))
                .hasSize(1);
    }

    @Test
    void aResultReportedIntoItsOwnLeaseExpiryIsEitherAcceptedOrRejectedButNeverBoth() {
        long projectId = client.registerProject();
        String cacheKey = "sha256:lease-race-" + UUID.randomUUID();
        Long planId =
                client.submitPlan(
                                projectId,
                                TestFixtures.singleTaskPlanWithShortTimeout(
                                        "rev-" + UUID.randomUUID(),
                                        "rev-0",
                                        "lease-race:build",
                                        cacheKey))
                        .id();
        BuildResponse build = client.createBuild(projectId, planId);

        long worker = client.registerWorker("worker-lease-race-" + UUID.randomUUID());
        ClaimedTaskResponse task = client.claimNamed(worker, "lease-race:build");

        // the declared timeout is 1s and the grace 2s, so this lands on top of the reclaim sweep
        ProtocolTestClient.sleepQuietly(3_000);
        HttpStatus status = client.reportResultStatus(task, true, 0, null, null);
        // three legitimate outcomes, depending on exactly when the reclaim sweep lands relative to
        // this report: it wins outright (204), it loses the lease outright because reclaim already
        // cleared it (403), or it read the row a moment before reclaim committed and its own
        // optimistic-version check then catches the race the reclaim just won (409, stale
        // transition) — none of the three ever accepts a report a reclaim has already superseded
        assertThat(status).isIn(HttpStatus.NO_CONTENT, HttpStatus.FORBIDDEN, HttpStatus.CONFLICT);

        TaskRun taskRun = taskRunRepository.findById(task.taskRunId()).orElseThrow();
        if (status == HttpStatus.NO_CONTENT) {
            // the report won: the outcome stands and the reclaim must not have started a second
            // attempt
            assertThat(taskRun.getState()).isIn(TaskRunState.SUCCEEDED, TaskRunState.CACHED);
            assertThat(taskRun.getAttemptCount()).isEqualTo(1);
            client.awaitBuildState(build.id(), BuildState.SUCCEEDED);
        } else {
            // the reclaim won: the stale report changed nothing and the task run is retryable again
            assertThat(taskRun.getState()).isNotIn(TaskRunState.SUCCEEDED, TaskRunState.CACHED);
            assertThat(taskRun.getArtifactDigest()).isNull();
            assertThat(client.getBuild(build.id()).state()).isNotEqualTo(BuildState.SUCCEEDED);
        }
        assertThat(taskAttemptRepository.findByTaskRunIdOrderByAttemptNumber(task.taskRunId()))
                .hasSizeLessThanOrEqualTo(taskRun.getAttemptCount());
    }

    private BuildResponse submitTwoIndependentTaskBuild(long projectId) {
        Long planId =
                client.submitPlan(
                                projectId,
                                TestFixtures.twoIndependentTaskPlan(
                                        "rev-" + UUID.randomUUID(), "rev-0"))
                        .id();
        return client.createBuild(projectId, planId);
    }

    /**
     * Two real worker loops racing for the same global queue until {@code done} says enough was
     * claimed.
     */
    private void runWorkersUntil(
            int workerCount,
            java.util.function.BooleanSupplier done,
            java.util.function.LongConsumer onClaim)
            throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        CountDownLatch finished = new CountDownLatch(workerCount);
        try {
            for (int i = 0; i < workerCount; i++) {
                String externalId = "worker-simultaneous-" + i + "-" + UUID.randomUUID();
                pool.submit(
                        () -> {
                            try {
                                long workerId = client.registerWorker(externalId);
                                while (!stop.get()) {
                                    client.heartbeat(workerId);
                                    Optional<ClaimedTaskResponse> claimed = client.claim(workerId);
                                    if (claimed.isEmpty()) {
                                        ProtocolTestClient.sleepQuietly(50);
                                        continue;
                                    }
                                    onClaim.accept(claimed.get().taskRunId());
                                    client.reportResult(claimed.get(), true, 0, null, null);
                                }
                            } finally {
                                finished.countDown();
                            }
                        });
            }
            for (int i = 0; i < 400 && !done.getAsBoolean(); i++) {
                ProtocolTestClient.sleepQuietly(100);
            }
            stop.set(true);
            assertThat(finished.await(60, TimeUnit.SECONDS))
                    .as("worker loops must stop cleanly")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
