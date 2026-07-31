package dev.forgeci.controlplane.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Wires the heartbeat/lease acceleration path: a Redis {@code expired} keyspace notification lets
 * {@link ExpiredKeyListener} react to a dead worker or lease within milliseconds instead of
 * waiting for the next MySQL sweep in {@code WorkerService}/{@code SchedulerService} — which stay
 * the source of truth and keep running unconditionally, so this acceleration is pure upside: if
 * Redis is flushed, restarted, or never enables notifications, the sweeps still catch everything,
 * just on their normal interval instead of instantly.
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    private final RedisConnectionFactory connectionFactory;

    public RedisConfig(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * Re-applied on every application-ready (not just once) because a Redis restart reloads server
     * config from its defaults, silently dropping this setting until it's set again. Best-effort —
     * Redis being unreachable at startup must never fail control-plane startup itself, matching
     * "no Redis-only authoritative state" (contracts.md#redis-responsibilities): the MySQL sweeps
     * work unconditionally, this only loses acceleration until Redis comes back.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void enableExpiredKeyNotifications() {
        try {
            connectionFactory.getConnection().serverCommands().setConfig("notify-keyspace-events", "Ex");
        } catch (RuntimeException redisUnavailable) {
            log.warn("could not enable Redis expired-key notifications, Redis unavailable: {}", redisUnavailable.getMessage());
        }
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(ExpiredKeyListener expiredKeyListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(expiredKeyListener, new PatternTopic("__keyevent@*__:expired"));
        return container;
    }
}
