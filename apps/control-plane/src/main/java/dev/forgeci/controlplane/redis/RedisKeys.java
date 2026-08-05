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

    /**
     * Keyed by attempt, not just by task run: a straggler and its speculative duplicate hold
     * separate leases on the same run, and one expiring must not look like the other expiring.
     */
    public static String lease(Long taskRunId, int attemptNumber) {
        return LEASE_PREFIX + taskRunId + ":" + attemptNumber;
    }

    public static Long workerIdFromHeartbeatKey(String key) {
        return Long.parseLong(key.substring(HEARTBEAT_PREFIX.length()));
    }

    /**
     * The (task run, attempt) a lease key refers to, or empty if it is not a parseable lease key.
     */
    public static java.util.Optional<LeaseKey> leaseKey(String key) {
        String[] parts = key.substring(LEASE_PREFIX.length()).split(":");
        if (parts.length != 2) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(
                    new LeaseKey(Long.parseLong(parts[0]), Integer.parseInt(parts[1])));
        } catch (NumberFormatException notALeaseKey) {
            return java.util.Optional.empty();
        }
    }

    public record LeaseKey(Long taskRunId, int attemptNumber) {}
}
