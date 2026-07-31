package dev.forgeci.controlplane.service;

import dev.forgeci.controlplane.domain.Build;
import dev.forgeci.controlplane.domain.BuildEventType;
import dev.forgeci.controlplane.domain.TaskAttempt;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.TaskRunState;
import dev.forgeci.controlplane.repository.BuildRepository;
import dev.forgeci.controlplane.repository.TaskAttemptRepository;
import dev.forgeci.controlplane.repository.TaskRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Valid {@link TaskRun} transitions and their side effects, per
 * spec/reference/architecture.md#state-machines. Every accepted transition emits exactly one
 * ordered {@code BuildEvent}, keyed by the owning build's sequence — so callers must go through
 * this class rather than mutating {@code TaskRun} state directly.
 */
@Component
public class TaskRunStateMachine {

    private static final Map<TaskRunState, Set<TaskRunState>> ALLOWED =
            Map.of(
                    TaskRunState.PENDING, Set.of(TaskRunState.READY, TaskRunState.SKIPPED),
                    TaskRunState.READY, Set.of(TaskRunState.LEASED, TaskRunState.CACHED),
                    TaskRunState.LEASED,
                            Set.of(TaskRunState.RUNNING, TaskRunState.RETRY_WAIT, TaskRunState.FAILED),
                    TaskRunState.RUNNING,
                            Set.of(TaskRunState.SUCCEEDED, TaskRunState.FAILED, TaskRunState.RETRY_WAIT),
                    TaskRunState.RETRY_WAIT, Set.of(TaskRunState.READY),
                    TaskRunState.SUCCEEDED, Set.of(),
                    TaskRunState.FAILED, Set.of(),
                    TaskRunState.CACHED, Set.of(),
                    TaskRunState.SKIPPED, Set.of());

    private static final Map<TaskRunState, BuildEventType> EVENT_FOR_TARGET =
            Map.ofEntries(
                    Map.entry(TaskRunState.READY, BuildEventType.TASK_RUN_READY),
                    Map.entry(TaskRunState.LEASED, BuildEventType.TASK_RUN_LEASED),
                    Map.entry(TaskRunState.RUNNING, BuildEventType.TASK_RUN_RUNNING),
                    Map.entry(TaskRunState.SUCCEEDED, BuildEventType.TASK_RUN_SUCCEEDED),
                    Map.entry(TaskRunState.FAILED, BuildEventType.TASK_RUN_FAILED),
                    Map.entry(TaskRunState.RETRY_WAIT, BuildEventType.TASK_RUN_RETRY_WAIT),
                    Map.entry(TaskRunState.CACHED, BuildEventType.TASK_RUN_CACHED),
                    Map.entry(TaskRunState.SKIPPED, BuildEventType.TASK_RUN_SKIPPED));

    private static final Logger log = LoggerFactory.getLogger(TaskRunStateMachine.class);

    private final TaskRunRepository taskRunRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final BuildRepository buildRepository;
    private final BuildEventPublisher events;

    public TaskRunStateMachine(
            TaskRunRepository taskRunRepository,
            TaskAttemptRepository taskAttemptRepository,
            BuildRepository buildRepository,
            BuildEventPublisher events) {
        this.taskRunRepository = taskRunRepository;
        this.taskAttemptRepository = taskAttemptRepository;
        this.buildRepository = buildRepository;
        this.events = events;
    }

    /**
     * Transitions the task run with id {@code taskRunId} to {@code target}. Locks the owning build
     * row first so this task run's event gets a sequence number consistent with any concurrent
     * transition on the same build (build or sibling task run).
     */
    @Transactional
    public TaskRun transition(Long taskRunId, long expectedVersion, TaskRunState target, TaskRunOutcome outcome) {
        TaskRun taskRun =
                taskRunRepository
                        .findById(taskRunId)
                        .orElseThrow(() -> new NotFoundException("task run " + taskRunId + " not found"));
        Build build =
                buildRepository
                        .findByIdForUpdate(taskRun.getBuild().getId())
                        .orElseThrow(() -> new NotFoundException("build " + taskRun.getBuild().getId() + " not found"));

        if (taskRun.getVersion() != expectedVersion) {
            throw new StaleTransitionException(
                    "task run "
                            + taskRunId
                            + " expected version "
                            + expectedVersion
                            + " but was "
                            + taskRun.getVersion());
        }

        TaskRunState current = taskRun.getState();
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
            throw new InvalidTransitionException(
                    "task run " + taskRunId + " cannot move from " + current + " to " + target);
        }

        taskRun.setState(target);
        Instant now = Instant.now();
        if (target == TaskRunState.READY) {
            taskRun.setReadyAt(now);
        } else if (target == TaskRunState.LEASED) {
            int attemptNumber = taskRun.getAttemptCount() + 1;
            taskRun.setAttemptCount(attemptNumber);
            taskAttemptRepository.save(new TaskAttempt(taskRun, attemptNumber, target));
        } else if (target == TaskRunState.RUNNING) {
            taskRun.setStartedAt(now);
            updateLatestAttempt(taskRun, TaskRunState.RUNNING, null, null);
        } else if (target == TaskRunState.SUCCEEDED || target == TaskRunState.FAILED) {
            taskRun.setCompletedAt(now);
            taskRun.setExitCode(outcome == null ? null : outcome.exitCode());
            taskRun.setFailureReason(outcome == null ? null : outcome.failureReason());
            taskRun.setArtifactDigest(outcome == null ? null : outcome.artifactDigest());
            updateLatestAttempt(
                    taskRun, target, outcome == null ? null : outcome.exitCode(), outcome == null ? null : outcome.failureReason());
        } else if (target == TaskRunState.RETRY_WAIT) {
            taskRun.setFailureReason(outcome == null ? null : outcome.failureReason());
            updateLatestAttempt(taskRun, target, null, outcome == null ? null : outcome.failureReason());
        }

        // flush now so the returned entity's bumped version is visible to a caller chaining transitions
        TaskRun saved = taskRunRepository.saveAndFlush(taskRun);

        events.publish(
                build,
                EVENT_FOR_TARGET.get(target),
                saved,
                Map.of("taskRunId", saved.getId(), "taskName", saved.getTaskName(), "from", current.name(), "to", target.name()));

        MDC.put("taskRunId", String.valueOf(saved.getId()));
        MDC.put("attemptId", String.valueOf(saved.getAttemptCount()));
        try {
            log.info("task run {} moved {} -> {}", saved.getTaskName(), current, target);
        } finally {
            MDC.remove("taskRunId");
            MDC.remove("attemptId");
        }
        return saved;
    }

    private void updateLatestAttempt(TaskRun taskRun, TaskRunState state, Integer exitCode, String failureReason) {
        List<TaskAttempt> attempts = taskAttemptRepository.findByTaskRunIdOrderByAttemptNumber(taskRun.getId());
        if (attempts.isEmpty()) {
            return;
        }
        TaskAttempt latest = attempts.get(attempts.size() - 1);
        latest.setState(state);
        if (state.isTerminal()) {
            latest.setCompletedAt(Instant.now());
            latest.setExitCode(exitCode);
            latest.setFailureReason(failureReason);
        }
        taskAttemptRepository.save(latest);
    }
}
