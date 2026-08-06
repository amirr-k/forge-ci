package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.Artifact;
import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.BuildState;
import dev.forgeci.controlplane.domain.TaskAttempt;
import dev.forgeci.controlplane.domain.TaskDefinitionEntity;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.domain.Worker;
import dev.forgeci.controlplane.domain.WorkerState;
import dev.forgeci.controlplane.redis.RedisKeys;
import dev.forgeci.controlplane.repository.BuildRepository;
import dev.forgeci.controlplane.repository.TaskAttemptRepository;
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
 * Owns the parts of the worker protocol that aren't a single state transition: matching a claiming
 * worker against the highest-priority ready task run, promoting a task run's dependents once it
 * completes, retry backoff and attempt capping, lease expiry reclamation, and rolling a task run's
 * terminal result up into the owning build's state. See spec/reference/architecture.md#scheduler
 * for the tie-break rule this implements.
 */
@Service
public class SchedulerService {

    /** Same cap applies whether a task run failed outright or its lease simply expired. */
    static final int MAX_ATTEMPTS = 3;

    private static final int CLAIM_CANDIDATE_LIMIT = 20;
    private static final Duration RETRY_BASE = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final TaskRunRepository taskRunRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final WorkerRepository workerRepository;
    private final BuildRepository buildRepository;
    private final TaskRunStateMachine taskRunStateMachine;
    private final BuildStateMachine buildStateMachine;
    private final RemoteArtifactService remoteArtifactService;
    private final TaskDurationEstimator durationEstimator;
    private final BuildMetrics metrics;
    private final StringRedisTemplate redis;
    private final Duration leaseGrace;
    private final Duration reclaimRetryDelay;
    private final SchedulingPolicy policy;
    private final boolean speculationEnabled;
    private final double speculationMultiplier;
    private final Duration speculationMinElapsed;
    private final int speculationMaxPerBuild;
    private final TransactionTemplate leaseTransactionTemplate;

    public SchedulerService(
            TaskRunRepository taskRunRepository,
            TaskAttemptRepository taskAttemptRepository,
            WorkerRepository workerRepository,
            BuildRepository buildRepository,
            TaskRunStateMachine taskRunStateMachine,
            BuildStateMachine buildStateMachine,
            RemoteArtifactService remoteArtifactService,
            TaskDurationEstimator durationEstimator,
            BuildMetrics metrics,
            StringRedisTemplate redis,
            @Value("${forge.scheduler.lease-grace-seconds:30}") long leaseGraceSeconds,
            @Value("${forge.scheduler.reclaim-retry-delay-ms:1000}") long reclaimRetryDelayMs,
            @Value("${forge.scheduler.policy:critical-path}") String policy,
            @Value("${forge.scheduler.speculation.enabled:false}") boolean speculationEnabled,
            @Value("${forge.scheduler.speculation.multiplier:1.5}") double speculationMultiplier,
            @Value("${forge.scheduler.speculation.min-elapsed-ms:5000}")
                    long speculationMinElapsedMs,
            @Value("${forge.scheduler.speculation.max-per-build:4}") int speculationMaxPerBuild,
            PlatformTransactionManager transactionManager) {
        this.taskRunRepository = taskRunRepository;
        this.taskAttemptRepository = taskAttemptRepository;
        this.workerRepository = workerRepository;
        this.buildRepository = buildRepository;
        this.taskRunStateMachine = taskRunStateMachine;
        this.buildStateMachine = buildStateMachine;
        this.remoteArtifactService = remoteArtifactService;
        this.durationEstimator = durationEstimator;
        this.metrics = metrics;
        this.redis = redis;
        this.leaseGrace = Duration.ofSeconds(leaseGraceSeconds);
        this.reclaimRetryDelay = Duration.ofMillis(reclaimRetryDelayMs);
        this.policy = SchedulingPolicy.from(policy);
        this.speculationEnabled = speculationEnabled;
        this.speculationMultiplier = speculationMultiplier;
        this.speculationMinElapsed = Duration.ofMillis(speculationMinElapsedMs);
        this.speculationMaxPerBuild = speculationMaxPerBuild;
        log.info("scheduler policy: {}, speculation: {}", this.policy, this.speculationEnabled);
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
     * joining this method's: {@link TaskRunStateMachine#transition} is itself
     * {@code @Transactional} with the default (joining) propagation, and a losing candidate's
     * exception would otherwise mark this whole method's transaction rollback-only — turning "skip
     * this candidate, try the next one" into an {@code UnexpectedRollbackException} on whatever
     * eventually succeeds.
     */
    @Transactional
    public Optional<TaskAttempt> claim(Long workerId) {
        Worker worker =
                workerRepository
                        .findByIdForUpdate(workerId)
                        .orElseThrow(
                                () -> new NotFoundException("worker " + workerId + " not found"));
        if (worker.getState() != WorkerState.ACTIVE) {
            return Optional.empty();
        }
        if (worker.getActiveLeaseCount() >= worker.getMaxConcurrency()) {
            return Optional.empty();
        }

        List<TaskRun> candidates = claimCandidates();
        for (TaskRun candidate : candidates) {
            Optional<TaskAttempt> leased = attemptLease(candidate, workerId);
            if (leased.isPresent()) {
                worker.setActiveLeaseCount(worker.getActiveLeaseCount() + 1);
                metrics.taskAttemptStarted();
                return leased;
            }
            log.debug("claim candidate {} no longer available, trying next", candidate.getId());
        }

        // only now, with no real work left to give this worker, is duplicating a straggler worth
        // it. Speculation therefore costs no throughput by construction: it can never take a slot
        // that an unstarted task would otherwise have used, which is the bound that matters far
        // more than the per-build cap below.
        Optional<TaskAttempt> speculative = speculateFor(worker);
        if (speculative.isPresent()) {
            worker.setActiveLeaseCount(worker.getActiveLeaseCount() + 1);
            metrics.taskAttemptStarted();
            metrics.speculativeAttemptStarted();
        }
        return speculative;
    }

    /**
     * Starts a bounded speculative duplicate of the longest-overdue straggler this worker is not
     * already running, if any task run has been running past {@link #stragglerThreshold} of its
     * historical duration. Returns empty when speculation is disabled, nothing is overdue, or the
     * owning build has already spent its speculation budget.
     */
    private Optional<TaskAttempt> speculateFor(Worker worker) {
        if (!speculationEnabled) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        List<TaskAttempt> candidates =
                taskAttemptRepository.findStragglerCandidates(
                        now.minus(speculationMinElapsed),
                        TaskRunState.RUNNING,
                        PageRequest.of(0, CLAIM_CANDIDATE_LIMIT));

        Map<Long, Map<String, Long>> estimatesByProject = new HashMap<>();
        for (TaskAttempt straggler : candidates) {
            TaskRun taskRun = straggler.getTaskRun();
            if (worker.getId().equals(straggler.getWorkerId())) {
                continue; // a second copy on the same worker duplicates the slowdown too
            }
            if (taskAttemptRepository.findLiveByTaskRunId(taskRun.getId()).size() != 1) {
                continue; // already speculated, or nothing live left to race
            }
            Long buildId = taskRun.getBuild().getId();
            if (taskAttemptRepository.countByTaskRunBuildIdAndSpeculativeTrue(buildId)
                    >= speculationMaxPerBuild) {
                continue;
            }
            Long projectId = taskRun.getBuild().getProject().getId();
            Map<String, Long> estimates =
                    estimatesByProject.computeIfAbsent(
                            projectId, durationEstimator::medianDurationsByTaskName);
            if (!isStraggling(straggler, estimates, now)) {
                continue;
            }
            Optional<TaskAttempt> started = attemptSpeculativeLease(taskRun, worker.getId());
            if (started.isPresent()) {
                log.info(
                        "speculating on task run {} ({}): attempt {} on worker {} duplicates"
                                + " attempt {} on worker {}",
                        taskRun.getId(),
                        taskRun.getTaskName(),
                        started.get().getAttemptNumber(),
                        worker.getId(),
                        straggler.getAttemptNumber(),
                        straggler.getWorkerId());
                return started;
            }
        }
        return Optional.empty();
    }

    /**
     * A task with no duration history is never treated as a straggler: without an estimate there is
     * nothing to be slow relative to, and speculating on every long task would just duplicate the
     * genuinely expensive ones.
     */
    private boolean isStraggling(TaskAttempt attempt, Map<String, Long> estimates, Instant now) {
        Long estimate = estimates.get(attempt.getTaskRun().getTaskName());
        if (estimate == null || estimate <= 0 || attempt.getStartedAt() == null) {
            return false;
        }
        long elapsed = Duration.between(attempt.getStartedAt(), now).toMillis();
        return elapsed >= stragglerThreshold(estimate);
    }

    long stragglerThreshold(long estimateMillis) {
        return Math.max(
                speculationMinElapsed.toMillis(), (long) (estimateMillis * speculationMultiplier));
    }

    /**
     * The ready-task ordering for the configured policy. All three read the same eligibility rule
     * and differ only in {@code order by}, so a policy change never affects which tasks may run,
     * only which of the eligible ones runs first.
     */
    private List<TaskRun> claimCandidates() {
        PageRequest limit = PageRequest.of(0, CLAIM_CANDIDATE_LIMIT);
        return switch (policy) {
            case FIFO -> taskRunRepository.findClaimCandidatesFifo(limit);
            case CRITICAL_PATH_DURATION -> taskRunRepository.findClaimCandidatesByDuration(limit);
            case CRITICAL_PATH -> taskRunRepository.findClaimCandidates(limit);
        };
    }

    private Optional<TaskAttempt> attemptLease(TaskRun candidate, Long workerId) {
        try {
            return Optional.of(
                    leaseTransactionTemplate.execute(status -> leaseAndStart(candidate, workerId)));
        } catch (StaleTransitionException
                | InvalidTransitionException
                | ObjectOptimisticLockingFailureException lostRace) {
            return Optional.empty();
        }
    }

    private Optional<TaskAttempt> attemptSpeculativeLease(TaskRun straggler, Long workerId) {
        try {
            return Optional.ofNullable(
                    leaseTransactionTemplate.execute(status -> speculateOn(straggler, workerId)));
        } catch (ObjectOptimisticLockingFailureException lostRace) {
            return Optional.empty();
        }
    }

    private TaskAttempt leaseAndStart(TaskRun candidate, Long workerId) {
        TaskRun leased =
                taskRunStateMachine.transition(
                        candidate.getId(),
                        candidate.getVersion(),
                        TaskRunState.LEASED,
                        TaskRunOutcome.NONE);
        // attempt_count is the retry budget, so only a real (non-speculative) attempt spends it
        leased.setAttemptCount(leased.getAttemptCount() + 1);
        leased = taskRunRepository.saveAndFlush(leased);
        TaskAttempt attempt = openAttempt(leased, workerId, false);

        // there is no separate "start" call in the fixed worker endpoint list (contracts.md) —
        // claiming a task run means the worker is about to execute it immediately, so this moves
        // it straight on to RUNNING in the same transaction.
        taskRunStateMachine.transition(
                leased.getId(), leased.getVersion(), TaskRunState.RUNNING, TaskRunOutcome.NONE);
        attempt.setState(TaskRunState.RUNNING);
        return taskAttemptRepository.saveAndFlush(attempt);
    }

    /**
     * Adds a second live attempt to an already-RUNNING task run. Deliberately performs no task-run
     * state transition: the run is already RUNNING and stays that way, so a speculative attempt is
     * invisible to the run's own state machine and to every consumer of build events except as an
     * extra attempt row.
     */
    private TaskAttempt speculateOn(TaskRun straggler, Long workerId) {
        TaskRun current = taskRunRepository.findById(straggler.getId()).orElse(null);
        if (current == null
                || current.getState() != TaskRunState.RUNNING
                || current.getWinningAttemptNumber() != null) {
            return null;
        }
        TaskAttempt attempt = openAttempt(current, workerId, true);
        attempt.setState(TaskRunState.RUNNING);
        return taskAttemptRepository.saveAndFlush(attempt);
    }

    /** Creates an attempt row holding its own lease, numbered after every attempt so far. */
    private TaskAttempt openAttempt(TaskRun taskRun, Long workerId, boolean speculative) {
        TaskDefinitionEntity definition = definitionOf(taskRun);
        int attemptNumber =
                taskAttemptRepository.findByTaskRunIdOrderByAttemptNumber(taskRun.getId()).stream()
                                .mapToInt(TaskAttempt::getAttemptNumber)
                                .max()
                                .orElse(0)
                        + 1;
        TaskAttempt attempt =
                new TaskAttempt(taskRun, attemptNumber, TaskRunState.LEASED, speculative);
        Instant leaseExpiration =
                Instant.now().plusSeconds(definition.getTimeoutSeconds()).plus(leaseGrace);
        attempt.setLeaseToken(UUID.randomUUID().toString());
        attempt.setWorkerId(workerId);
        attempt.setLeaseExpiration(leaseExpiration);
        attempt = taskAttemptRepository.saveAndFlush(attempt);
        markLeaseInRedis(taskRun.getId(), attemptNumber, attempt.getLeaseToken(), leaseExpiration);
        mirrorLeaseOntoRun(taskRun, attempt);
        return attempt;
    }

    /**
     * Keeps the task_runs lease columns pointing at the newest live attempt. Nothing reads them to
     * decide anything — every lease check goes through task_attempts — they exist so the API and
     * operator queries can still see "who is running this" without joining.
     */
    private void mirrorLeaseOntoRun(TaskRun taskRun, TaskAttempt attempt) {
        taskRun.setWorkerId(attempt.getWorkerId());
        taskRun.setLeaseToken(attempt.getLeaseToken());
        taskRun.setLeaseExpiration(attempt.getLeaseExpiration());
        taskRunRepository.saveAndFlush(taskRun);
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
        TaskRun taskRun =
                taskRunRepository
                        .findById(taskRunId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "task run " + taskRunId + " not found"));
        TaskAttempt attempt =
                taskAttemptRepository
                        .findByTaskRunIdAndAttemptNumber(taskRunId, attemptId)
                        .orElseThrow(
                                () ->
                                        new LeaseRejectedException(
                                                "task run "
                                                        + taskRunId
                                                        + " has no attempt "
                                                        + attemptId));
        if (!workerId.equals(attempt.getWorkerId())
                || !leaseToken.equals(attempt.getLeaseToken())) {
            throw new LeaseRejectedException(
                    "task run " + taskRunId + " lease token/worker/attempt mismatch");
        }
        if (!attempt.isLive()) {
            // this attempt is already resolved: either a redelivered copy of the report that won
            // (idempotent no-op, which is what makes Kafka redelivery safe) or one whose lease was
            // expired or superseded while the worker was still running it
            if (Integer.valueOf(attemptId).equals(taskRun.getWinningAttemptNumber())) {
                return taskRun;
            }
            throw new LeaseRejectedException(
                    "task run " + taskRunId + " attempt " + attemptId + " is no longer live");
        }

        metrics.resultSubmitted();
        releaseWorkerLease(workerId);
        clearLeaseInRedis(taskRunId, attemptId);

        if (success) {
            if (!winRace(taskRunId, attemptId)) {
                // a sibling attempt's result was accepted first. This one is discarded whole: no
                // state change, no artifact promotion, no dependents released. Discarding it is
                // sound because both attempts ran the same task at the same cache key, so the
                // accepted artifact is interchangeable with this one.
                finishAttempt(attempt, TaskRunState.SUCCEEDED, exitCode, null);
                metrics.duplicateResultRejected();
                // re-read rather than trusting the copy loaded above: claiming the winner clears
                // the persistence context, so the in-memory task run still shows no winner at all
                log.info(
                        "task run {} attempt {} lost the result race to attempt {}",
                        taskRunId,
                        attemptId,
                        taskRunRepository
                                .findById(taskRunId)
                                .map(TaskRun::getWinningAttemptNumber)
                                .orElse(null));
                return taskRunRepository.findById(taskRunId).orElse(taskRun);
            }
            finishAttempt(attempt, TaskRunState.SUCCEEDED, exitCode, null);
            supersedeLiveSiblings(taskRunId, attemptId);
            TaskRun result =
                    taskRunStateMachine.transition(
                            taskRunId,
                            taskRun.getVersion(),
                            TaskRunState.SUCCEEDED,
                            new TaskRunOutcome(exitCode, null, artifactDigest));
            metrics.resultAccepted(attempt.isSpeculative());
            if (result.getStartedAt() != null && result.getCompletedAt() != null) {
                metrics.taskCompleted(
                        Duration.between(result.getStartedAt(), result.getCompletedAt()));
            }
            promoteReadyDependents(result);
            maybeCompleteBuild(result.getBuild().getId());
            return result;
        }

        finishAttempt(attempt, TaskRunState.FAILED, exitCode, failureReason);

        // a failure only decides the run's fate once nothing else is still racing for it —
        // otherwise a speculative copy that is about to succeed would be thrown away in favour of
        // a retry, which is strictly worse than just letting the sibling finish
        if (!taskAttemptRepository.findLiveByTaskRunId(taskRunId).isEmpty()) {
            log.debug(
                    "task run {} attempt {} failed but a sibling attempt is still live",
                    taskRunId,
                    attemptId);
            return taskRun;
        }
        if (!winRace(taskRunId, attemptId)) {
            metrics.duplicateResultRejected();
            return taskRun;
        }
        metrics.resultAccepted(attempt.isSpeculative());

        if (taskRun.getAttemptCount() < MAX_ATTEMPTS) {
            TaskRun result =
                    taskRunStateMachine.transition(
                            taskRunId,
                            taskRun.getVersion(),
                            TaskRunState.RETRY_WAIT,
                            new TaskRunOutcome(exitCode, failureReason, null));
            result.setRetryAt(Instant.now().plus(backoff(taskRun.getAttemptCount())));
            taskRunRepository.save(result);
            metrics.taskRetried();
            return result;
        }

        TaskRun result =
                taskRunStateMachine.transition(
                        taskRunId,
                        taskRun.getVersion(),
                        TaskRunState.FAILED,
                        new TaskRunOutcome(exitCode, failureReason, null));
        failBuild(result.getBuild().getId());
        return result;
    }

    /**
     * The single point at which concurrent attempts are resolved. Returns true for exactly one
     * caller per generation of attempts — the database decides, not the application, so two reports
     * landing in the same millisecond on different control-plane threads still produce one accepted
     * result. This is idempotent-acceptance, not exactly-once execution: both attempts really did
     * run.
     */
    private boolean winRace(Long taskRunId, int attemptId) {
        return taskRunRepository.claimWinningAttempt(taskRunId, attemptId) == 1;
    }

    /**
     * The lease token is deliberately left in place. It still identifies who this attempt belonged
     * to, which is what lets a redelivered report from the winner be recognised as a duplicate of
     * an accepted result (a no-op) rather than an unrecognised caller — the attempt's state, not
     * the presence of its token, is what decides whether a report may still be applied.
     */
    private void finishAttempt(
            TaskAttempt attempt, TaskRunState state, Integer exitCode, String failureReason) {
        attempt.setState(state);
        attempt.setCompletedAt(Instant.now());
        attempt.setExitCode(exitCode);
        attempt.setFailureReason(failureReason);
        taskAttemptRepository.saveAndFlush(attempt);
    }

    /**
     * Retires the losing attempts once a winner exists. Their workers keep running the duplicated
     * command to completion — nothing cancels them remotely — but their eventual reports now fail
     * the liveness check and are rejected instead of applied.
     */
    private void supersedeLiveSiblings(Long taskRunId, int winningAttemptNumber) {
        for (TaskAttempt sibling : taskAttemptRepository.findLiveByTaskRunId(taskRunId)) {
            if (sibling.getAttemptNumber() == winningAttemptNumber) {
                continue;
            }
            finishAttempt(
                    sibling,
                    TaskRunState.SKIPPED,
                    null,
                    "superseded by attempt " + winningAttemptNumber);
            releaseWorkerLease(sibling.getWorkerId());
            clearLeaseInRedis(taskRunId, sibling.getAttemptNumber());
        }
    }

    /** RETRY_WAIT task runs whose backoff has elapsed become claimable again. */
    @Scheduled(fixedDelayString = "${forge.scheduler.retry-sweep-interval-ms:2000}")
    @Transactional
    public void promoteDueRetries() {
        for (TaskRun taskRun :
                taskRunRepository.findByStateAndRetryAtBefore(
                        TaskRunState.RETRY_WAIT, Instant.now())) {
            try {
                taskRunStateMachine.transition(
                        taskRun.getId(),
                        taskRun.getVersion(),
                        TaskRunState.READY,
                        TaskRunOutcome.NONE);
            } catch (StaleTransitionException | InvalidTransitionException raced) {
                log.debug(
                        "task run {} already moved on before its retry promotion", taskRun.getId());
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
        for (TaskAttempt attempt : taskAttemptRepository.findExpired(Instant.now())) {
            reclaimIfStillExpired(attempt);
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
    public void reclaimExpiredLease(Long taskRunId, int attemptNumber) {
        taskAttemptRepository
                .findByTaskRunIdAndAttemptNumber(taskRunId, attemptNumber)
                .ifPresent(this::reclaimIfStillExpired);
    }

    /**
     * Expires one attempt's lease. The owning task run is only reclaimed once this was its last
     * live attempt — with speculation, one attempt's worker dying while its duplicate is still
     * running is not a reason to retry the task, it is exactly the case speculation exists to
     * absorb.
     */
    /**
     * Reclaims every attempt a worker was holding the moment that worker is declared dead. Without
     * this, recovery would wait out each attempt's lease, which is the task's own declared timeout
     * plus the grace period — so a crash during a two-minute task would cost two minutes to notice
     * even though the worker stopped heartbeating seconds in. Detection cost becomes one heartbeat
     * window instead, independent of how long the task itself was going to take.
     */
    @Transactional
    public void reclaimLeasesOfWorker(Long workerId) {
        for (TaskAttempt attempt : taskAttemptRepository.findLiveByWorkerId(workerId)) {
            reclaimAttempt(attempt, "worker " + workerId + " stopped heartbeating");
        }
    }

    private void reclaimIfStillExpired(TaskAttempt attempt) {
        boolean stillExpired =
                attempt.getLeaseExpiration() != null
                        && attempt.getLeaseExpiration().isBefore(Instant.now());
        if (!attempt.isLive() || !stillExpired) {
            return;
        }
        reclaimAttempt(attempt, "lease expired");
    }

    private void reclaimAttempt(TaskAttempt attempt, String reason) {
        if (!attempt.isLive()) {
            return;
        }
        TaskRun taskRun = attempt.getTaskRun();
        finishAttempt(attempt, TaskRunState.FAILED, null, reason);
        releaseWorkerLease(attempt.getWorkerId());
        clearLeaseInRedis(taskRun.getId(), attempt.getAttemptNumber());
        metrics.leaseExpired();

        if (!taskAttemptRepository.findLiveByTaskRunId(taskRun.getId()).isEmpty()) {
            log.debug(
                    "task run {} attempt {} expired but a sibling attempt is still live",
                    taskRun.getId(),
                    attempt.getAttemptNumber());
            return;
        }
        if (taskRun.getState() != TaskRunState.LEASED
                && taskRun.getState() != TaskRunState.RUNNING) {
            return;
        }
        if (!winRace(taskRun.getId(), attempt.getAttemptNumber())) {
            return; // a sibling's result was already accepted for this generation
        }
        try {
            if (taskRun.getAttemptCount() < MAX_ATTEMPTS) {
                TaskRun result =
                        taskRunStateMachine.transition(
                                taskRun.getId(),
                                taskRun.getVersion(),
                                TaskRunState.RETRY_WAIT,
                                new TaskRunOutcome(null, reason, null));
                // no exponential backoff here. Backoff exists to stop hammering a task that keeps
                // failing on its own; a reclaimed lease means the worker disappeared, and the work
                // itself was never shown to be bad — so making crash recovery wait out a doubling
                // delay would just add dead time to the one case that most needs to be fast.
                result.setRetryAt(Instant.now().plus(reclaimRetryDelay));
                result.setLeaseToken(null);
                taskRunRepository.save(result);
                metrics.taskRetried();
            } else {
                TaskRun result =
                        taskRunStateMachine.transition(
                                taskRun.getId(),
                                taskRun.getVersion(),
                                TaskRunState.FAILED,
                                new TaskRunOutcome(null, reason, null));
                result.setLeaseToken(null);
                taskRunRepository.save(result);
                failBuild(result.getBuild().getId());
            }
        } catch (StaleTransitionException | InvalidTransitionException raced) {
            log.debug(
                    "task run {} already moved on before its lease expiry was reclaimed",
                    taskRun.getId());
        }
    }

    /**
     * Re-arms Redis lease keys from MySQL's {@code lease_expiration} — the reconciliation path that
     * lets active builds recover their acceleration after a Redis flush/restart. A lease whose
     * expiration has already passed by the time this runs is skipped: {@link #reclaimExpiredLeases}
     * will pick it up on its own next pass rather than this method re-deriving that decision.
     */
    @Scheduled(fixedDelayString = "${forge.redis.reconcile-interval-ms:15000}")
    @Transactional(readOnly = true)
    public void reconcileRedisLeases() {
        try {
            Instant now = Instant.now();
            for (TaskRun taskRun :
                    taskRunRepository.findByStateIn(
                            List.of(TaskRunState.LEASED, TaskRunState.RUNNING))) {
                for (TaskAttempt attempt :
                        taskAttemptRepository.findLiveByTaskRunId(taskRun.getId())) {
                    if (attempt.getLeaseExpiration() != null
                            && attempt.getLeaseExpiration().isAfter(now)
                            && attempt.getLeaseToken() != null) {
                        markLeaseInRedis(
                                taskRun.getId(),
                                attempt.getAttemptNumber(),
                                attempt.getLeaseToken(),
                                attempt.getLeaseExpiration());
                    }
                }
            }
        } catch (RuntimeException redisUnavailable) {
            log.debug(
                    "skipping Redis lease reconciliation, Redis unavailable: {}",
                    redisUnavailable.getMessage());
        }
    }

    private void markLeaseInRedis(
            Long taskRunId, int attemptNumber, String leaseToken, Instant leaseExpiration) {
        Duration ttl = Duration.between(Instant.now(), leaseExpiration);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(RedisKeys.lease(taskRunId, attemptNumber), leaseToken, ttl);
        } catch (RuntimeException redisUnavailable) {
            log.debug(
                    "could not mark lease for task run {} attempt {} in Redis: {}",
                    taskRunId,
                    attemptNumber,
                    redisUnavailable.getMessage());
        }
    }

    private void clearLeaseInRedis(Long taskRunId, int attemptNumber) {
        try {
            redis.delete(RedisKeys.lease(taskRunId, attemptNumber));
        } catch (RuntimeException redisUnavailable) {
            log.debug(
                    "could not clear lease for task run {} attempt {} in Redis: {}",
                    taskRunId,
                    attemptNumber,
                    redisUnavailable.getMessage());
        }
    }

    private void releaseWorkerLease(Long workerId) {
        if (workerId == null) {
            return;
        }
        workerRepository
                .findByIdForUpdate(workerId)
                .ifPresent(
                        worker ->
                                worker.setActiveLeaseCount(
                                        Math.max(0, worker.getActiveLeaseCount() - 1)));
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
                            .allMatch(
                                    dep ->
                                            !stateByName.containsKey(dep)
                                                    || isSatisfied(stateByName.get(dep)));
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
     *
     * <p>A {@code CACHED} outcome must cascade to dependents exactly like a worker-reported {@code
     * SUCCEEDED} does ({@link #isSatisfied} already treats them identically) — nothing else will
     * ever call {@link #reportResult} for a task that was never claimed by a worker, so without
     * this a cache hit on any task with dependents would strand the rest of the build in {@code
     * PENDING} forever. Completion itself is left to the caller (every call site already checks it
     * right after triggering promotion) — calling {@link #maybeCompleteBuild} again from here would
     * re-enter it inside the same transaction for no benefit.
     */
    @Transactional
    public void promoteToReadyOrCached(TaskRun taskRun, Long projectId) {
        TaskRun ready;
        try {
            ready =
                    taskRunStateMachine.transition(
                            taskRun.getId(),
                            taskRun.getVersion(),
                            TaskRunState.READY,
                            TaskRunOutcome.NONE);
        } catch (StaleTransitionException | InvalidTransitionException raced) {
            log.debug(
                    "task run {} readiness promotion raced with another transition",
                    taskRun.getId());
            return;
        }
        Optional<Artifact> hit = remoteArtifactService.verifiedHit(ready.getCacheKey(), projectId);
        if (hit.isEmpty()) {
            return;
        }
        TaskRun cached;
        try {
            cached =
                    taskRunStateMachine.transition(
                            ready.getId(),
                            ready.getVersion(),
                            TaskRunState.CACHED,
                            new TaskRunOutcome(null, null, hit.get().getDigest()));
        } catch (StaleTransitionException | InvalidTransitionException raced) {
            log.debug(
                    "task run {} cache-hit promotion raced with another transition", ready.getId());
            return;
        }
        promoteReadyDependents(cached);
    }

    private static boolean isSatisfied(TaskRunState state) {
        return state == TaskRunState.SUCCEEDED || state == TaskRunState.CACHED;
    }

    /**
     * Public so {@link BuildService} can also check completion right after materializing a build
     * whose tasks were all cache hits.
     */
    @Transactional
    public void maybeCompleteBuild(Long buildId) {
        // counted in the database rather than by scanning loaded entities — see
        // TaskRunRepository.countUnfinished for why a managed entity can report a stale state here
        // and leave a fully-succeeded build stuck RUNNING
        if (taskRunRepository.countUnfinished(buildId) > 0) {
            return;
        }
        try {
            Build build = buildRepository.findById(buildId).orElseThrow();
            Build completed =
                    buildStateMachine.transition(buildId, build.getVersion(), BuildState.SUCCEEDED);
            if (completed.getStartedAt() != null && completed.getCompletedAt() != null) {
                metrics.buildSucceeded(
                        Duration.between(completed.getStartedAt(), completed.getCompletedAt()));
            }
        } catch (StaleTransitionException | InvalidTransitionException alreadyResolved) {
            log.debug("build {} already resolved", buildId);
        }
    }

    private void failBuild(Long buildId) {
        try {
            Build build = buildRepository.findById(buildId).orElseThrow();
            Build failed =
                    buildStateMachine.transition(buildId, build.getVersion(), BuildState.FAILED);
            if (failed.getStartedAt() != null && failed.getCompletedAt() != null) {
                metrics.buildFailed(
                        Duration.between(failed.getStartedAt(), failed.getCompletedAt()));
            }
        } catch (StaleTransitionException | InvalidTransitionException alreadyResolved) {
            log.debug("build {} already resolved", buildId);
        }
    }

    /**
     * Log lines for one attempt, validated against the same lease every report is checked against.
     */
    @Transactional(readOnly = true)
    public void appendLogs(
            Long taskRunId, Long workerId, String leaseToken, int attemptId, List<String> lines) {
        TaskRun taskRun =
                taskRunRepository
                        .findById(taskRunId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "task run " + taskRunId + " not found"));
        TaskAttempt attempt =
                taskAttemptRepository
                        .findByTaskRunIdAndAttemptNumber(taskRunId, attemptId)
                        .orElseThrow(
                                () ->
                                        new LeaseRejectedException(
                                                "task run "
                                                        + taskRunId
                                                        + " has no attempt "
                                                        + attemptId));
        if (!workerId.equals(attempt.getWorkerId())
                || !leaseToken.equals(attempt.getLeaseToken())) {
            throw new LeaseRejectedException(
                    "task run " + taskRunId + " lease token/worker/attempt mismatch");
        }
        for (String line : lines) {
            log.info(
                    "[build {} task {} attempt {} worker {}] {}",
                    taskRun.getBuild().getId(),
                    taskRun.getTaskName(),
                    attemptId,
                    workerId,
                    line);
        }
    }

    public static TaskDefinitionEntity definitionOf(TaskRun taskRun) {
        for (TaskDefinitionEntity definition : taskRun.getBuild().getPlanSubmission().getTasks()) {
            if (definition.getTaskName().equals(taskRun.getTaskName())) {
                return definition;
            }
        }
        throw new IllegalStateException(
                "no task definition for task run "
                        + taskRun.getId()
                        + " ("
                        + taskRun.getTaskName()
                        + ")");
    }

    static Duration backoff(int attemptsSoFar) {
        long factor = 1L << Math.min(attemptsSoFar, 4);
        return RETRY_BASE.multipliedBy(factor);
    }
}
