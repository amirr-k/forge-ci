package dev.forgeci.controlplane.redis;

/**
 * Key naming for the ephemeral, TTL-only state Redis is allowed to hold
 * (contracts.md#redis-responsibilities).
 */
public final class RedisKeys {

    public static final String HEARTBEAT_PREFIX = "forge:worker:heartbeat:";
    public static final String LEASE_PREFIX = "forge:lease:";

    private RedisKeys() {}

    public static String heartbeat(Long workerId) {
        return HEARTBEAT_PREFIX + workerId;
    }

    public static String lease(Long taskRunId) {
        return LEASE_PREFIX + taskRunId;
    }

    public static Long workerIdFromHeartbeatKey(String key) {
        return Long.parseLong(key.substring(HEARTBEAT_PREFIX.length()));
    }

    public static Long taskRunIdFromLeaseKey(String key) {
        return Long.parseLong(key.substring(LEASE_PREFIX.length()));
    }
}
