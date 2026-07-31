package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.Artifact;
import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.domain.TaskDefinitionEntity;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.domain.Worker;
import dev.forgeci.controlplane.domain.WorkerState;
import dev.forgeci.controlplane.redis.RedisKeys;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
    private static final Duration RETRY_BASE = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final TaskRunRepository taskRunRepository;
    private final WorkerRepository workerRepository;
    private final BuildRepository buildRepository;
    private final TaskRunStateMachine taskRunStateMachine;
    private final BuildStateMachine buildStateMachine;
    private final RemoteArtifactService remoteArtifactService;
    private final BuildMetrics metrics;
    private final StringRedisTemplate redis;
    private final Duration leaseGrace;
    private final TransactionTemplate leaseTransactionTemplate;

    public SchedulerService(
            TaskRunRepository taskRunRepository,
            WorkerRepository workerRepository,
            BuildRepository buildRepository,
            TaskRunStateMachine taskRunStateMachine,
            BuildStateMachine buildStateMachine,
            RemoteArtifactService remoteArtifactService,
            BuildMetrics metrics,
            StringRedisTemplate redis,
            @Value("${forge.scheduler.lease-grace-seconds:30}") long leaseGraceSeconds,
            PlatformTransactionManager transactionManager) {
        this.taskRunRepository = taskRunRepository;
        this.workerRepository = workerRepository;
        this.buildRepository = buildRepository;
        this.taskRunStateMachine = taskRunStateMachine;
        this.buildStateMachine = buildStateMachine;
        this.remoteArtifactService = remoteArtifactService;
        this.metrics = metrics;
        this.redis = redis;
        this.leaseGrace = Duration.ofSeconds(leaseGraceSeconds);
        DefaultTransactionDefinition requiresNew = new DefaultTransactionDefinition();
        requiresNew.setPropagationBehavior(DefaultTransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.leaseTransactionTemplate = new TransactionTemplate(transactionManager, requiresNew);
    }

    /**
     * Leases the highest-priority claimable task run to {@code workerId}, if the worker has spare
     * concurrency and at least one candidate is still available by the time this worker's turn to
     * lease it comes up — a candidate lost to a concurrent claim (optimistic-lock failure) or that
     * stopped being eligible between the query and the lock is skipped in favor of the next one.
     *
     * <p>Each candidate attempt runs in its own {@code REQUIRES_NEW} transaction rather than
     * joining this method's: {@link TaskRunStateMachine#transition} is itself {@code @Transactional}
     * with the default (joining) propagation, and a losing candidate's exception would otherwise
     * mark this whole method's transaction rollback-only — turning "skip this candidate, try the
     * next one" into an {@code UnexpectedRollbackException} on whatever eventually succeeds.
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
            Optional<TaskRun> leased = attemptLease(candidate, workerId);
            if (leased.isPresent()) {
                worker.setActiveLeaseCount(worker.getActiveLeaseCount() + 1);
                metrics.taskAttemptStarted();
                return leased;
            }
            log.debug("claim candidate {} no longer available, trying next", candidate.getId());
        }
        return Optional.empty();
    }

    private Optional<TaskRun> attemptLease(TaskRun candidate, Long workerId) {
        try {
            return Optional.of(leaseTransactionTemplate.execute(status -> leaseAndStart(candidate, workerId)));
        } catch (StaleTransitionException | InvalidTransitionException | ObjectOptimisticLockingFailureException lostRace) {
            return Optional.empty();
        }
    }

    private TaskRun leaseAndStart(TaskRun candidate, Long workerId) {
        TaskRun leased =
                taskRunStateMachine.transition(candidate.getId(), candidate.getVersion(), TaskRunState.LEASED, TaskRunOutcome.NONE);
        TaskDefinitionEntity definition = definitionOf(leased);
        leased.setLeaseToken(UUID.randomUUID().toString());
        leased.setWorkerId(workerId);
        Instant leaseExpiration = Instant.now().plusSeconds(definition.getTimeoutSeconds()).plus(leaseGrace);
        leased.setLeaseExpiration(leaseExpiration);
        leased = taskRunRepository.saveAndFlush(leased);
        markLeaseInRedis(leased.getId(), leased.getLeaseToken(), leaseExpiration);

        // there is no separate "start" call in the fixed worker endpoint list (contracts.md) —
        // claiming a task run means the worker is about to execute it immediately, so this moves
        // it straight on to RUNNING in the same transaction. The re-fetch inside transition()
        // already picks up the lease fields just flushed above.
        return taskRunStateMachine.transition(leased.getId(), leased.getVersion(), TaskRunState.RUNNING, TaskRunOutcome.NONE);
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
        clearLeaseInRedis(taskRunId);

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
     * LEASED}/{@code RUNNING}. This is the unconditional safety net: it runs on MySQL's own {@code
     * lease_expiration} column regardless of Redis's state, so a flushed or unreachable Redis never
     * stalls recovery — it just falls back to this sweep's own interval instead of the faster
     * Redis-driven path in {@link #reclaimExpiredLease}.
     */
    @Scheduled(fixedDelayString = "${forge.scheduler.lease-sweep-interval-ms:5000}")
    @Transactional
    public void reclaimExpiredLeases() {
        for (TaskRun taskRun :
                taskRunRepository.findByStateInAndLeaseExpirationBefore(
                        List.of(TaskRunState.LEASED, TaskRunState.RUNNING), Instant.now())) {
            reclaimIfStillExpired(taskRun);
        }
    }

    /**
     * The Redis-accelerated counterpart to {@link #reclaimExpiredLeases}: triggered by {@code
     * ExpiredKeyListener} the moment a lease's Redis TTL key expires, instead of waiting for the
     * next periodic sweep. Re-reads the task run and re-checks its MySQL {@code lease_expiration}
     * before doing anything — the Redis event is only ever a hint that speeds up detection, never
     * itself the authority for whether a lease has actually expired.
     */
    @Transactional
    public void reclaimExpiredLease(Long taskRunId) {
        taskRunRepository.findById(taskRunId).ifPresent(this::reclaimIfStillExpired);
    }

    private void reclaimIfStillExpired(TaskRun taskRun) {
        boolean stillLeased = taskRun.getState() == TaskRunState.LEASED || taskRun.getState() == TaskRunState.RUNNING;
        boolean stillExpired = taskRun.getLeaseExpiration() != null && taskRun.getLeaseExpiration().isBefore(Instant.now());
        if (!stillLeased || !stillExpired) {
            return;
        }
        releaseWorkerLease(taskRun.getWorkerId());
        clearLeaseInRedis(taskRun.getId());
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

    /**
     * Re-arms Redis lease keys from MySQL's {@code lease_expiration} — the reconciliation path that
     * lets active builds recover their acceleration after a Redis flush/restart. A lease whose
     * expiration has already passed by the time this runs is skipped: {@link #reclaimExpiredLeases}
     * will pick it up on its own next pass rather than this method re-deriving that decision.
     */
    @Scheduled(fixedDelayString = "${forge.redis.reconcile-interval-ms:15000}")
    public void reconcileRedisLeases() {
        try {
            Instant now = Instant.now();
            for (TaskRun taskRun : taskRunRepository.findByStateIn(List.of(TaskRunState.LEASED, TaskRunState.RUNNING))) {
                if (taskRun.getLeaseExpiration() != null && taskRun.getLeaseExpiration().isAfter(now) && taskRun.getLeaseToken() != null) {
                    markLeaseInRedis(taskRun.getId(), taskRun.getLeaseToken(), taskRun.getLeaseExpiration());
                }
            }
        } catch (RuntimeException redisUnavailable) {
            log.debug("skipping Redis lease reconciliation, Redis unavailable: {}", redisUnavailable.getMessage());
        }
    }

    private void markLeaseInRedis(Long taskRunId, String leaseToken, Instant leaseExpiration) {
        Duration ttl = Duration.between(Instant.now(), leaseExpiration);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(RedisKeys.lease(taskRunId), leaseToken, ttl);
        } catch (RuntimeException redisUnavailable) {
            log.debug("could not mark lease for task run {} in Redis: {}", taskRunId, redisUnavailable.getMessage());
        }
    }

    private void clearLeaseInRedis(Long taskRunId) {
        try {
            redis.delete(RedisKeys.lease(taskRunId));
        } catch (RuntimeException redisUnavailable) {
            log.debug("could not clear lease for task run {} in Redis: {}", taskRunId, redisUnavailable.getMessage());
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
            // a dependency absent from this build's task list was never selected for it — exactly
            // like ReadinessEvaluator.isImmediatelyReady, that means it was already satisfied
            // before the build was planned, not that it's now permanently unsatisfiable
            boolean allSatisfied =
                    definition.getDependsOn().stream()
                            .allMatch(dep -> !stateByName.containsKey(dep) || isSatisfied(stateByName.get(dep)));
            if (allSatisfied) {
                promoteToReadyOrCached(sibling, completed.getBuild().getProject().getId());
            }
        }
    }

    /**
     * A dependency-complete task run goes {@code READY} first (always — this is what timestamps
     * {@code readyAt} for the FIFO tie-break) and then immediately on to {@code CACHED} if a
     * verified artifact already exists for its cache key, per
     * spec/reference/architecture.md#affected-task-analysis ("convert valid hits to CACHED").
     * Reused directly by {@link BuildService} for a build's initially-ready task runs.
     */
    @Transactional
    public void promoteToReadyOrCached(TaskRun taskRun, Long projectId) {
        TaskRun ready;
        try {
            ready = taskRunStateMachine.transition(taskRun.getId(), taskRun.getVersion(), TaskRunState.READY, TaskRunOutcome.NONE);
        } catch (StaleTransitionException | InvalidTransitionException raced) {
            log.debug("task run {} readiness promotion raced with another transition", taskRun.getId());
            return;
        }
        Optional<Artifact> hit = remoteArtifactService.verifiedHit(ready.getCacheKey(), projectId);
        if (hit.isEmpty()) {
            return;
        }
        try {
            taskRunStateMachine.transition(
                    ready.getId(), ready.getVersion(), TaskRunState.CACHED, new TaskRunOutcome(null, null, hit.get().getDigest()));
        } catch (StaleTransitionException | InvalidTransitionException raced) {
            log.debug("task run {} cache-hit promotion raced with another transition", ready.getId());
        }
    }

    private static boolean isSatisfied(TaskRunState state) {
        return state == TaskRunState.SUCCEEDED || state == TaskRunState.CACHED;
    }

    /** Public so {@link BuildService} can also check completion right after materializing a build whose tasks were all cache hits. */
    @Transactional
    public void maybeCompleteBuild(Long buildId) {
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
