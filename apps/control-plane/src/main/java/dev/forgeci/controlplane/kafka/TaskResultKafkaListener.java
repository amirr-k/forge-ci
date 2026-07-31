package dev.forgeci.controlplane.kafka;

import dev.forgeci.controlplane.service.SchedulerService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * An alternate, durable ingress for worker results alongside the direct {@code POST
 * /api/task-runs/{id}/result} call — both delegate to the exact same {@link
 * SchedulerService#reportResult}, so a message redelivered after a rebalance or a retry hits the
 * same idempotent, lease-checked path a duplicate HTTP call would: a report against a task run that
 * already resolved with a matching lease is a no-op, not a re-applied effect.
 */
@Component
public class TaskResultKafkaListener {

    private final SchedulerService schedulerService;

    public TaskResultKafkaListener(SchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    @KafkaListener(
            topics = KafkaTopics.TASK_RESULTS,
            containerFactory = "taskResultListenerContainerFactory")
    public void onMessage(TaskResultEvent event) {
        schedulerService.reportResult(
                event.taskRunId(),
                event.workerId(),
                event.leaseToken(),
                event.attemptId(),
                event.success(),
                event.exitCode(),
                event.failureReason(),
                event.artifactDigest());
    }
}
