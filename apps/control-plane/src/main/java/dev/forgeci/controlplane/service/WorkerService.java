package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.Worker;
import dev.forgeci.controlplane.domain.WorkerState;
import dev.forgeci.controlplane.repository.WorkerRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and heartbeat for worker processes. A worker's {@code externalId} is its own
 * stable identity (e.g. a Compose replica name) — re-registering the same id after a restart
 * reactivates the existing row instead of creating a duplicate, matching this codebase's
 * idempotent-acceptance convention elsewhere.
 */
@Service
public class WorkerService {

    /** A worker missing this many heartbeat intervals is marked unhealthy and excluded from claims. */
    private static final int MISSED_HEARTBEATS_BEFORE_UNHEALTHY = 3;

    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

    private final WorkerRepository workerRepository;
    private final Duration heartbeatInterval;

    public WorkerService(
            WorkerRepository workerRepository,
            @Value("${forge.worker.heartbeat-interval-ms:5000}") long heartbeatIntervalMs) {
        this.workerRepository = workerRepository;
        this.heartbeatInterval = Duration.ofMillis(heartbeatIntervalMs);
    }

    @Transactional
    public Worker register(String externalId, List<String> capabilities, int maxConcurrency, String versionLabel) {
        Worker worker =
                workerRepository
                        .findByExternalId(externalId)
                        .orElseGet(() -> workerRepository.save(new Worker(externalId, capabilities, maxConcurrency, versionLabel)));
        worker.setState(WorkerState.ACTIVE);
        worker.setLastHeartbeatAt(Instant.now());
        log.info("worker {} ({}) registered, maxConcurrency={}", externalId, worker.getId(), maxConcurrency);
        return worker;
    }

    @Transactional
    public Worker heartbeat(Long workerId) {
        Worker worker = workerRepository.findById(workerId).orElseThrow(() -> new NotFoundException("worker " + workerId + " not found"));
        worker.setLastHeartbeatAt(Instant.now());
        worker.setState(WorkerState.ACTIVE);
        return worker;
    }

    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    /** Runs at the heartbeat cadence: a worker silent for {@link #MISSED_HEARTBEATS_BEFORE_UNHEALTHY} intervals stops receiving claims. */
    @Scheduled(fixedDelayString = "${forge.worker.heartbeat-interval-ms:5000}")
    @Transactional
    public void markStaleWorkersUnhealthy() {
        Instant cutoff = Instant.now().minus(heartbeatInterval.multipliedBy(MISSED_HEARTBEATS_BEFORE_UNHEALTHY));
        List<Worker> stale = workerRepository.findByStateAndLastHeartbeatAtBefore(WorkerState.ACTIVE, cutoff);
        for (Worker worker : stale) {
            worker.setState(WorkerState.UNHEALTHY);
            log.warn("worker {} ({}) marked unhealthy — no heartbeat since {}", worker.getExternalId(), worker.getId(), worker.getLastHeartbeatAt());
        }
    }
}
