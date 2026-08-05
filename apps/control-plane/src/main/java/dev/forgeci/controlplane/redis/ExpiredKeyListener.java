package dev.forgeci.controlplane.redis;

import dev.forgeci.controlplane.service.SchedulerService;
import dev.forgeci.controlplane.service.WorkerService;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to a Redis {@code expired} event for a heartbeat or lease key — the accelerated path
 * alongside {@code WorkerService}/{@code SchedulerService}'s periodic MySQL sweeps. Every handler
 * re-validates against MySQL before mutating anything: an expired key is a hint that something may
 * be dead, never proof by itself (the worker could have re-heartbeated a moment after this event
 * was queued, or an unrelated Redis restart could have wiped the key early).
 */
@Component
public class ExpiredKeyListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ExpiredKeyListener.class);

    private final WorkerService workerService;
    private final SchedulerService schedulerService;

    public ExpiredKeyListener(WorkerService workerService, SchedulerService schedulerService) {
        this.workerService = workerService;
        this.schedulerService = schedulerService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String key = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            if (key.startsWith(RedisKeys.HEARTBEAT_PREFIX)) {
                workerService.onHeartbeatKeyExpired(RedisKeys.workerIdFromHeartbeatKey(key));
            } else if (key.startsWith(RedisKeys.LEASE_PREFIX)) {
                RedisKeys.leaseKey(key)
                        .ifPresent(
                                lease ->
                                        schedulerService.reclaimExpiredLease(
                                                lease.taskRunId(), lease.attemptNumber()));
            }
        } catch (RuntimeException e) {
            log.warn("failed to handle Redis expiry for key {}: {}", key, e.getMessage());
        }
    }
}
