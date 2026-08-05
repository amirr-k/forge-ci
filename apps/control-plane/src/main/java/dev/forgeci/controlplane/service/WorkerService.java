package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.Worker;
import dev.forgeci.controlplane.domain.WorkerState;
import dev.forgeci.controlplane.redis.RedisKeys;
import dev.forgeci.controlplane.repository.WorkerRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and heartbeat for worker processes. A worker's {@code externalId} is its own stable
 * identity (e.g. a Compose replica name) — re-registering the same id after a restart reactivates
 * the existing row instead of creating a duplicate, matching this codebase's idempotent-acceptance
 * convention elsewhere.
 *
 * <p>MySQL's {@code last_heartbeat_at} stays the sole authority on liveness — {@link
 * #markStaleWorkersUnhealthy()} sweeps it unconditionally regardless of Redis's state. The Redis
 * heartbeat key ({@link RedisKeys#heartbeat}) only shortens how quickly a dead worker is noticed:
 * its expiry drives {@link #onHeartbeatKeyExpired}, which still re-checks MySQL before acting.
 */
@Service
public class WorkerService {

    /**
     * A worker missing this many heartbeat intervals is marked unhealthy and excluded from claims.
     */
    private static final int MISSED_HEARTBEATS_BEFORE_UNHEALTHY = 3;

    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

    private final WorkerRepository workerRepository;
    private final SchedulerService schedulerService;
    private final StringRedisTemplate redis;
    private final Duration heartbeatInterval;
    private final Duration unhealthyAfter;

    public WorkerService(
            WorkerRepository workerRepository,
            SchedulerService schedulerService,
            StringRedisTemplate redis,
            @Value("${forge.worker.heartbeat-interval-ms:5000}") long heartbeatIntervalMs) {
        this.workerRepository = workerRepository;
        this.schedulerService = schedulerService;
        this.redis = redis;
        this.heartbeatInterval = Duration.ofMillis(heartbeatIntervalMs);
        this.unhealthyAfter = heartbeatInterval.multipliedBy(MISSED_HEARTBEATS_BEFORE_UNHEALTHY);
    }

    @Transactional
    public Worker register(
            String externalId, List<String> capabilities, int maxConcurrency, String versionLabel) {
        Worker worker =
                workerRepository
                        .findByExternalId(externalId)
                        .orElseGet(
                                () ->
                                        workerRepository.save(
                                                new Worker(
                                                        externalId,
                                                        capabilities,
                                                        maxConcurrency,
                                                        versionLabel)));
        worker.setState(WorkerState.ACTIVE);
        worker.setLastHeartbeatAt(Instant.now());
        worker.setCrashRequested(false);
        log.info(
                "worker {} ({}) registered, maxConcurrency={}",
                externalId,
                worker.getId(),
                maxConcurrency);
        markAliveInRedis(worker.getId());
        return worker;
    }

    public record HeartbeatResult(Worker worker, boolean shouldCrash) {}

    /** Consumes (clears) any pending crash-injection request in the same transaction it reports. */
    @Transactional
    public HeartbeatResult heartbeat(Long workerId) {
        Worker worker =
                workerRepository
                        .findById(workerId)
                        .orElseThrow(
                                () -> new NotFoundException("worker " + workerId + " not found"));
        worker.setLastHeartbeatAt(Instant.now());
        worker.setState(WorkerState.ACTIVE);
        boolean shouldCrash = worker.isCrashRequested();
        worker.setCrashRequested(false);
        markAliveInRedis(workerId);
        return new HeartbeatResult(worker, shouldCrash);
    }

    /**
     * Backend half of crash injection (architecture.md's worker protocol) — the actual halt happens
     * worker-side on its next heartbeat.
     */
    @Transactional
    public void requestCrash(Long workerId) {
        Worker worker =
                workerRepository
                        .findById(workerId)
                        .orElseThrow(
                                () -> new NotFoundException("worker " + workerId + " not found"));
        worker.setCrashRequested(true);
        log.info("crash requested for worker {} ({})", worker.getExternalId(), workerId);
    }

    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * Runs at the heartbeat cadence: a worker silent for {@link
     * #MISSED_HEARTBEATS_BEFORE_UNHEALTHY} intervals stops receiving claims.
     */
    @Scheduled(fixedDelayString = "${forge.worker.heartbeat-interval-ms:5000}")
    @Transactional
    public void markStaleWorkersUnhealthy() {
        Instant cutoff = Instant.now().minus(unhealthyAfter);
        List<Worker> stale =
                workerRepository.findByStateAndLastHeartbeatAtBefore(WorkerState.ACTIVE, cutoff);
        for (Worker worker : stale) {
            worker.setState(WorkerState.UNHEALTHY);
            log.warn(
                    "worker {} ({}) marked unhealthy — no heartbeat since {}",
                    worker.getExternalId(),
                    worker.getId(),
                    worker.getLastHeartbeatAt());
            schedulerService.reclaimLeasesOfWorker(worker.getId());
        }
    }

    /**
     * Fires on a Redis heartbeat-key expiry, well before the next {@link
     * #markStaleWorkersUnhealthy} sweep would otherwise notice — but only acts if MySQL's own
     * timestamp agrees the worker is actually stale, so a Redis key that merely expired a moment
     * before a fresh heartbeat landed in MySQL never causes a false unhealthy mark.
     */
    @Transactional
    public void onHeartbeatKeyExpired(Long workerId) {
        workerRepository
                .findById(workerId)
                .filter(w -> w.getState() == WorkerState.ACTIVE)
                .filter(
                        w ->
                                w.getLastHeartbeatAt() == null
                                        || w.getLastHeartbeatAt()
                                                .isBefore(Instant.now().minus(unhealthyAfter)))
                .ifPresent(
                        worker -> {
                            worker.setState(WorkerState.UNHEALTHY);
                            log.warn(
                                    "worker {} ({}) marked unhealthy via Redis-accelerated detection — no heartbeat since {}",
                                    worker.getExternalId(),
                                    workerId,
                                    worker.getLastHeartbeatAt());
                            schedulerService.reclaimLeasesOfWorker(workerId);
                        });
    }

    /**
     * Re-arms Redis heartbeat keys from MySQL's {@code last_heartbeat_at} — the reconciliation path
     * that lets the system recover correctly after a Redis flush or restart (contracts.md#redis-
     * responsibilities): the acceleration layer is rebuilt, but nothing here is required for
     * correctness since {@link #markStaleWorkersUnhealthy} never depends on Redis.
     */
    @Scheduled(fixedDelayString = "${forge.redis.reconcile-interval-ms:15000}")
    public void reconcileRedisFromDatabase() {
        try {
            Instant cutoff = Instant.now().minus(unhealthyAfter);
            for (Worker worker :
                    workerRepository.findByStateAndLastHeartbeatAtBefore(
                            WorkerState.ACTIVE, Instant.now())) {
                if (worker.getLastHeartbeatAt() != null
                        && worker.getLastHeartbeatAt().isAfter(cutoff)) {
                    markAliveInRedis(worker.getId());
                }
            }
        } catch (RuntimeException redisUnavailable) {
            log.debug(
                    "skipping Redis heartbeat reconciliation, Redis unavailable: {}",
                    redisUnavailable.getMessage());
        }
    }

    private void markAliveInRedis(Long workerId) {
        try {
            redis.opsForValue().set(RedisKeys.heartbeat(workerId), "1", unhealthyAfter);
        } catch (RuntimeException redisUnavailable) {
            log.debug(
                    "could not mark worker {} alive in Redis: {}",
                    workerId,
                    redisUnavailable.getMessage());
        }
    }
}
