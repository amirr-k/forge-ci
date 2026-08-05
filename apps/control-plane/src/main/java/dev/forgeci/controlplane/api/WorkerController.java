package dev.forgeci.controlplane.api;

import dev.forgeci.controlplane.domain.TaskAttempt;
import dev.forgeci.controlplane.domain.TaskDefinitionEntity;
import dev.forgeci.controlplane.domain.TaskRun;
import dev.forgeci.controlplane.domain.Worker;
import dev.forgeci.controlplane.service.SchedulerService;
import dev.forgeci.controlplane.service.WorkerService;
import dev.forgeci.protocol.ClaimedTaskResponse;
import dev.forgeci.protocol.HeartbeatResponse;
import dev.forgeci.protocol.LogChunkRequest;
import dev.forgeci.protocol.TaskResultReportRequest;
import dev.forgeci.protocol.WorkerRegistrationRequest;
import dev.forgeci.protocol.WorkerRegistrationResponse;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST implementation of the worker protocol fixed in
 * spec/reference/architecture.md#worker-protocol.
 */
@RestController
public class WorkerController {

    private final WorkerService workerService;
    private final SchedulerService schedulerService;

    public WorkerController(WorkerService workerService, SchedulerService schedulerService) {
        this.workerService = workerService;
        this.schedulerService = schedulerService;
    }

    @PostMapping("/api/workers/register")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkerRegistrationResponse register(@RequestBody WorkerRegistrationRequest request) {
        Worker worker =
                workerService.register(
                        request.externalId(),
                        request.capabilities(),
                        request.maxConcurrency(),
                        request.versionLabel());
        return new WorkerRegistrationResponse(
                worker.getId(), workerService.heartbeatInterval().toMillis());
    }

    @PostMapping("/api/workers/{id}/heartbeat")
    public HeartbeatResponse heartbeat(@PathVariable("id") Long workerId) {
        WorkerService.HeartbeatResult result = workerService.heartbeat(workerId);
        return new HeartbeatResponse(result.shouldCrash());
    }

    /**
     * Admin/test crash-injection trigger — the mechanism phase 7's public "Crash a Worker" demo
     * button drives. The worker consumes and clears the flag on its next heartbeat and halts
     * immediately, so the effect is only visible once that heartbeat lands.
     */
    @PostMapping("/api/workers/{id}/crash")
    public ResponseEntity<Void> crash(@PathVariable("id") Long workerId) {
        workerService.requestCrash(workerId);
        return ResponseEntity.accepted().build();
    }

    /**
     * {@code 204} means no claimable task run right now — not an error, the worker should poll
     * again.
     */
    @PostMapping("/api/workers/{id}/claim")
    public ResponseEntity<ClaimedTaskResponse> claim(@PathVariable("id") Long workerId) {
        Optional<TaskAttempt> leased = schedulerService.claim(workerId);
        return leased.map(attempt -> ResponseEntity.ok(toResponse(attempt)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/api/task-runs/{id}/logs")
    public ResponseEntity<Void> logs(
            @PathVariable("id") Long taskRunId, @RequestBody LogChunkRequest request) {
        schedulerService.appendLogs(
                taskRunId,
                request.workerId(),
                request.leaseToken(),
                request.attemptId(),
                request.lines());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/task-runs/{id}/result")
    public ResponseEntity<Void> result(
            @PathVariable("id") Long taskRunId, @RequestBody TaskResultReportRequest request) {
        schedulerService.reportResult(
                taskRunId,
                request.workerId(),
                request.leaseToken(),
                request.attemptId(),
                request.success(),
                request.exitCode(),
                request.failureReason(),
                request.artifactDigest());
        return ResponseEntity.noContent().build();
    }

    /**
     * The wire shape is unchanged: {@code attemptId} and {@code leaseToken} now come from the
     * attempt rather than the task run, so a worker running a speculative duplicate reports under
     * its own identity without knowing that is what it is doing.
     */
    private static ClaimedTaskResponse toResponse(TaskAttempt attempt) {
        TaskRun taskRun = attempt.getTaskRun();
        TaskDefinitionEntity definition = SchedulerService.definitionOf(taskRun);
        return new ClaimedTaskResponse(
                taskRun.getId(),
                taskRun.getBuild().getId(),
                taskRun.getBuild().getProject().getId(),
                taskRun.getTaskName(),
                taskRun.getCacheKey(),
                definition.getCommand(),
                definition.getOutputs(),
                definition.getEnvironment(),
                definition.getTimeoutSeconds(),
                attempt.getAttemptNumber(),
                attempt.getWorkerId(),
                attempt.getLeaseToken());
    }
}
