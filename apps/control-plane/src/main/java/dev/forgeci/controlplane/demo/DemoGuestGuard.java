package dev.forgeci.controlplane.demo;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Guest-demo safety, enforced server-side regardless of what the UI lets a visitor click
 * (contracts.md#redis-responsibilities: bounded distributed locks, guest-demo rate limiting).
 * Redis is acceleration/ephemeral-only here too — if it's unreachable, both checks fail closed
 * (deny), never open, since there is no MySQL fallback for either concern and "no Redis" must
 * never mean "no limit."
 */
@Component
public class DemoGuestGuard {

    private static final String BUILD_LOCK_KEY = "forge:demo:build-lock";
    private static final String RATE_LIMIT_PREFIX = "forge:demo:rate:";
    private static final Duration BUILD_LOCK_TTL = Duration.ofMinutes(5);
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(30);
    private static final int MAX_WORKER_COUNT = 4;

    private final StringRedisTemplate redis;

    public DemoGuestGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** True if the caller acquired the single global "one guest demo build in flight" slot. */
    public boolean tryAcquireBuildSlot(String buildToken) {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(BUILD_LOCK_KEY, buildToken, BUILD_LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException redisUnavailable) {
            return false;
        }
    }

    public void releaseBuildSlot(String buildToken) {
        try {
            String holder = redis.opsForValue().get(BUILD_LOCK_KEY);
            if (buildToken.equals(holder)) {
                redis.delete(BUILD_LOCK_KEY);
            }
        } catch (RuntimeException redisUnavailable) {
            // the TTL still expires it; nothing else to do if Redis is down
        }
    }

    /** True if {@code clientKey} (e.g. remote IP) may start another guest build right now. */
    public boolean tryConsumeRateLimit(String clientKey) {
        try {
            String key = RATE_LIMIT_PREFIX + clientKey;
            Boolean firstInWindow = redis.opsForValue().setIfAbsent(key, "1", RATE_LIMIT_WINDOW);
            return Boolean.TRUE.equals(firstInWindow);
        } catch (RuntimeException redisUnavailable) {
            return false;
        }
    }

    public int boundWorkerCount(int requested) {
        return Math.max(1, Math.min(requested, MAX_WORKER_COUNT));
    }

    public int maxWorkerCount() {
        return MAX_WORKER_COUNT;
    }
}
