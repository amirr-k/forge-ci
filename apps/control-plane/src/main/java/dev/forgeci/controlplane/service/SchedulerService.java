package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.domain.TaskDefinitionEntity;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.domain.Worker;
import dev.forgeci.controlplane.domain.WorkerState;
import dev.forgeci.controlplane.repository.BuildRepository;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import dev.forgeci.controlplane.repository.WorkerRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the parts of the worker protocol that aren't a single state transition: matching a
 * claiming worker against the highest-priority ready task run, promoting a task run's dependents
 * once it completes, retry backoff and attempt capping, lease expiry reclamation, and rolling a
 * task run's terminal result up into the owning build's state. See
 * spec/reference/architecture.md#scheduler for the tie-break rule this implements.
 */
@Service
public class SchedulerService {

    /** Same cap applies whether a task run failed outright or its lease simply expired. */
    static final int MAX_ATTEMPTS = 3;

    private static final int CLAIM_CANDIDATE_LIMIT = 20;
    private static final Duration LEASE_GRACE = Duration.ofSeconds(30);
    private static final Duration RETRY_BASE = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final TaskRunRepository taskRunRepository;
    private final WorkerRepository workerRepository;
    private final BuildRepository buildRepository;
    private final TaskRunStateMachine taskRunStateMachine;
    private final BuildStateMachine buildStateMachine;
    private final BuildMetrics metrics;

    public SchedulerService(
            TaskRunRepository taskRunRepository,
            WorkerRepository workerRepository,
            BuildRepository buildRepository,
            TaskRunStateMachine taskRunStateMachine,
            BuildStateMachine buildStateMachine,
            BuildMetrics metrics) {
        this.taskRunRepository = taskRunRepository;
        this.workerRepository = workerRepository;
        this.buildRepository = buildRepository;
        this.taskRunStateMachine = taskRunStateMachine;
        this.buildStateMachine = buildStateMachine;
        this.metrics = metrics;
    }

    /**
     * Leases the highest-priority claimable task run to {@code workerId}, if the worker has spare
     * concurrency and at least one candidate is still available by the time this worker's turn to
     * lease it comes up — a candidate lost to a concurrent claim (optimistic-lock failure) or that
     * stopped being eligible between the query and the lock is skipped in favor of the next one.
     */
    @Transactional
    public Optional<TaskRun> claim(Long workerId) {
        Worker worker =
                workerRepository.findByIdForUpdate(workerId).orElseThrow(() -> new NotFoundException("worker " + workerId + " not found"));
        if (worker.getState() != WorkerState.ACTIVE) {
            return Optional.empty();
        }
        if (worker.getActiveLeaseCount() >= worker.getMaxConcurrency()) {
            return Optional.empty();
        }

        List<TaskRun> candidates = taskRunRepository.findClaimCandidates(PageRequest.of(0, CLAIM_CANDIDATE_LIMIT));
        for (TaskRun candidate : candidates) {
            try {
                TaskRun leased =
                        taskRunStateMachine.transition(
                                candidate.getId(), candidate.getVersion(), TaskRunState.LEASED, TaskRunOutcome.NONE);
                TaskDefinitionEntity definition = definitionOf(leased);
                leased.setLeaseToken(UUID.randomUUID().toString());
                leased.setWorkerId(workerId);
                leased.setLeaseExpiration(
                        Instant.now().plusSeconds(definition.getTimeoutSeconds()).plus(LEASE_GRACE));
                taskRunRepository.save(leased);

                worker.setActiveLeaseCount(worker.getActiveLeaseCount() + 1);
                metrics.taskAttemptStarted();
                return Optional.of(leased);
            } catch (StaleTransitionException | InvalidTransitionException | ObjectOptimisticLockingFailureException lostRace) {
                log.debug("claim candidate {} no longer available, trying next", candidate.getId());
            }
        }
        return Optional.empty();
    }

    /**
     * Records a worker's terminal report for one attempt. Idempotent: a report that no longer
     * matches the task run's current lease (already resolved, or a redelivered duplicate of an
     * already-accepted result) is a no-op that returns the task run unchanged rather than raising —
     * this is what keeps a redelivered Kafka {@code task-results} message from re-applying an
     * effect that already happened.
     */
    @Transactional
    public TaskRun reportResult(
            Long taskRunId,
            Long workerId,
            String leaseToken,
            int attemptId,
            boolean success,
            Integer exitCode,
            String failureReason,
            String artifactDigest) {
        TaskRun taskRun = taskRunRepository.findById(taskRunId).orElseThrow(() -> new NotFoundException("task run " + taskRunId + " not found"));

        if (taskRun.getState().isTerminal()) {
            if (matchesLease(taskRun, workerId, leaseToken, attemptId)) {
                return taskRun; // duplicate of an already-accepted result — no-op
            }
            throw new LeaseRejectedException("task run " + taskRunId + " already resolved by a different attempt");
        }
        if (!matchesLease(taskRun, workerId, leaseToken, attemptId)) {
            throw new LeaseRejectedException("task run " + taskRunId + " lease token/worker/attempt mismatch");
        }

        releaseWorkerLease(workerId);

        if (success) {
            TaskRun result =
                    taskRunStateMachine.transition(
                            taskRunId, taskRun.getVersion(), TaskRunState.SUCCEEDED, new TaskRunOutcome(exitCode, null, artifactDigest));
            if (result.getStartedAt() != null && result.getCompletedAt() != null) {
                metrics.taskCompleted(Duration.between(result.getStartedAt(), result.getCompletedAt()));
            }
            promoteReadyDependents(result);
            maybeCompleteBuild(result.getBuild().getId());
            return result;
        }

        if (taskRun.getAttemptCount() < MAX_ATTEMPTS) {
            TaskRun result =
                    taskRunStateMachine.transition(
                            taskRunId, taskRun.getVersion(), TaskRunState.RETRY_WAIT, new TaskRunOutcome(exitCode, failureReason, null));
            result.setRetryAt(Instant.now().plus(backoff(taskRun.getAttemptCount())));
            taskRunRepository.save(result);
            metrics.taskRetried();
            return result;
        }

        TaskRun result =
                taskRunStateMachine.transition(
                        taskRunId, taskRun.getVersion(), TaskRunState.FAILED, new TaskRunOutcome(exitCode, failureReason, null));
        failBuild(result.getBuild().getId());
        return result;
    }

    /** RETRY_WAIT task runs whose backoff has elapsed become claimable again. */
    @Scheduled(fixedDelayString = "${forge.scheduler.retry-sweep-interval-ms:2000}")
    @Transactional
    public void promoteDueRetries() {
        for (TaskRun taskRun : taskRunRepository.findByStateAndRetryAtBefore(TaskRunState.RETRY_WAIT, Instant.now())) {
            try {
                taskRunStateMachine.transition(taskRun.getId(), taskRun.getVersion(), TaskRunState.READY, TaskRunOutcome.NONE);
            } catch (StaleTransitionException | InvalidTransitionException raced) {
                log.debug("task run {} already moved on before its retry promotion", taskRun.getId());
            }
        }
    }

    /**
     * A lease past its expiration is never trusted again, even if the worker eventually reports —
     * {@link #reportResult} will reject that report once the task run has moved past {@code
     * LEASED}/{@code RUNNING}. This phase's leases are DB-backed expiration only, not a
     * Redis-accelerated reservation — full crash-recovery reconciliation is phase 6.
     */
    @Scheduled(fixedDelayString = "${forge.scheduler.lease-sweep-interval-ms:5000}")
    @Transactional
    public void reclaimExpiredLeases() {
        for (TaskRun taskRun :
                taskRunRepository.findByStateInAndLeaseExpirationBefore(
                        List.of(TaskRunState.LEASED, TaskRunState.RUNNING), Instant.now())) {
            releaseWorkerLease(taskRun.getWorkerId());
            metrics.leaseExpired();
            try {
                if (taskRun.getAttemptCount() < MAX_ATTEMPTS) {
                    TaskRun result =
                            taskRunStateMachine.transition(
                                    taskRun.getId(),
                                    taskRun.getVersion(),
                                    TaskRunState.RETRY_WAIT,
                                    new TaskRunOutcome(null, "lease expired", null));
                    result.setRetryAt(Instant.now().plus(backoff(taskRun.getAttemptCount())));
                    taskRunRepository.save(result);
                    metrics.taskRetried();
                } else {
                    TaskRun result =
                            taskRunStateMachine.transition(
                                    taskRun.getId(), taskRun.getVersion(), TaskRunState.FAILED, new TaskRunOutcome(null, "lease expired", null));
                    failBuild(result.getBuild().getId());
                }
            } catch (StaleTransitionException | InvalidTransitionException raced) {
                log.debug("task run {} already moved on before its lease expiry was reclaimed", taskRun.getId());
            }
        }
    }

    private boolean matchesLease(TaskRun taskRun, Long workerId, String leaseToken, int attemptId) {
        return workerId.equals(taskRun.getWorkerId())
                && leaseToken.equals(taskRun.getLeaseToken())
                && attemptId == taskRun.getAttemptCount();
    }

    private void releaseWorkerLease(Long workerId) {
        if (workerId == null) {
            return;
        }
        workerRepository
                .findByIdForUpdate(workerId)
                .ifPresent(worker -> worker.setActiveLeaseCount(Math.max(0, worker.getActiveLeaseCount() - 1)));
    }

    private void promoteReadyDependents(TaskRun completed) {
        List<TaskRun> siblings = taskRunRepository.findByBuildId(completed.getBuild().getId());
        Map<String, TaskRunState> stateByName = new HashMap<>();
        for (TaskRun sibling : siblings) {
            stateByName.put(sibling.getTaskName(), sibling.getState());
        }
        Map<String, TaskDefinitionEntity> definitionByName = new HashMap<>();
        for (TaskDefinitionEntity def : completed.getBuild().getPlanSubmission().getTasks()) {
            definitionByName.put(def.getTaskName(), def);
        }

        for (TaskRun sibling : siblings) {
            if (sibling.getState() != TaskRunState.PENDING) {
                continue;
            }
            TaskDefinitionEntity definition = definitionByName.get(sibling.getTaskName());
            boolean allSatisfied =
                    definition.getDependsOn().stream()
                            .allMatch(dep -> isSatisfied(stateByName.get(dep)));
            if (allSatisfied) {
                try {
                    taskRunStateMachine.transition(sibling.getId(), sibling.getVersion(), TaskRunState.READY, TaskRunOutcome.NONE);
                } catch (StaleTransitionException | InvalidTransitionException raced) {
                    log.debug("task run {} readiness promotion raced with another transition", sibling.getId());
                }
            }
        }
    }

    private static boolean isSatisfied(TaskRunState state) {
        return state == TaskRunState.SUCCEEDED || state == TaskRunState.CACHED;
    }

    private void maybeCompleteBuild(Long buildId) {
        List<TaskRun> runs = taskRunRepository.findByBuildId(buildId);
        boolean allDone =
                runs.stream().allMatch(r -> r.getState() == TaskRunState.SUCCEEDED || r.getState() == TaskRunState.CACHED);
        if (!allDone) {
            return;
        }
        try {
            Build build = buildRepository.findById(buildId).orElseThrow();
            Build completed = buildStateMachine.transition(buildId, build.getVersion(), BuildState.SUCCEEDED);
            if (completed.getStartedAt() != null && completed.getCompletedAt() != null) {
                metrics.buildSucceeded(Duration.between(completed.getStartedAt(), completed.getCompletedAt()));
            }
        } catch (StaleTransitionException | InvalidTransitionException alreadyResolved) {
            log.debug("build {} already resolved", buildId);
        }
    }

    private void failBuild(Long buildId) {
        try {
            Build build = buildRepository.findById(buildId).orElseThrow();
            Build failed = buildStateMachine.transition(buildId, build.getVersion(), BuildState.FAILED);
            if (failed.getStartedAt() != null && failed.getCompletedAt() != null) {
                metrics.buildFailed(Duration.between(failed.getStartedAt(), failed.getCompletedAt()));
            }
        } catch (StaleTransitionException | InvalidTransitionException alreadyResolved) {
            log.debug("build {} already resolved", buildId);
        }
    }

    /** Log lines for one attempt, validated against the same lease every report is checked against. */
    @Transactional(readOnly = true)
    public void appendLogs(Long taskRunId, Long workerId, String leaseToken, int attemptId, List<String> lines) {
        TaskRun taskRun = taskRunRepository.findById(taskRunId).orElseThrow(() -> new NotFoundException("task run " + taskRunId + " not found"));
        if (!matchesLease(taskRun, workerId, leaseToken, attemptId)) {
            throw new LeaseRejectedException("task run " + taskRunId + " lease token/worker/attempt mismatch");
        }
        for (String line : lines) {
            log.info("[build {} task {} attempt {} worker {}] {}", taskRun.getBuild().getId(), taskRun.getTaskName(), attemptId, workerId, line);
        }
    }

    public static TaskDefinitionEntity definitionOf(TaskRun taskRun) {
        for (TaskDefinitionEntity definition : taskRun.getBuild().getPlanSubmission().getTasks()) {
            if (definition.getTaskName().equals(taskRun.getTaskName())) {
                return definition;
            }
        }
        throw new IllegalStateException("no task definition for task run " + taskRun.getId() + " (" + taskRun.getTaskName() + ")");
    }

    private static Duration backoff(int attemptsSoFar) {
        long factor = 1L << Math.min(attemptsSoFar, 4);
        return RETRY_BASE.multipliedBy(factor);
    }
}
