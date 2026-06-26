package fr.enimaloc.catapult.service.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Centralised API-side metrics. All instrumentation calls go through this
 * class — counters are resolved lazily via the registry cache.
 */
@Component
public class CatapultApiMetrics {

    private final MeterRegistry registry;

    public CatapultApiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // -------------------------------------------------------------------------
    // Redis publishing
    // -------------------------------------------------------------------------

    /**
     * Records one event published to a Redis pub/sub channel.
     *
     * @param redisChannel the raw Redis channel name (e.g. {@code catapult:events:user:abc})
     */
    public void recordRedisPublished(String redisChannel) {
        registry.counter("catapult.redis.events_published",
                "channel_class", classify(redisChannel)).increment();
    }

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

    /**
     * Records a notification lifecycle action.
     *
     * @param action one of {@code created}, {@code read}, {@code markAllRead}, {@code deleted}
     */
    public void recordNotificationAction(String action) {
        registry.counter("catapult.notification.actions", "action", action).increment();
    }

    // -------------------------------------------------------------------------
    // Broadcasts
    // -------------------------------------------------------------------------

    /**
     * Records a broadcast attempt.
     *
     * @param name     the broadcast event name from the request body
     * @param accepted {@code true} if the broadcast was published; {@code false} if
     *                 rejected by validation or rate-limiting
     */
    public void recordBroadcastAttempt(String name, boolean accepted) {
        registry.counter("catapult.broadcast.attempts",
                "name", name, "accepted", String.valueOf(accepted)).increment();
    }

    // -------------------------------------------------------------------------
    // Static helpers
    // -------------------------------------------------------------------------

    /**
     * Derives a {@code channel_class} label from a Redis pub/sub channel name.
     * Strips the {@code catapult:events:} prefix and takes the first
     * colon-delimited segment (or the whole remainder when there is none).
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code catapult:events:user:abc123} → {@code user}</li>
     *   <li>{@code catapult:events:channel:abc123} → {@code channel}</li>
     *   <li>{@code catapult:events:global} → {@code global}</li>
     * </ul>
     */
    public static String classify(String redisChannel) {
        String after = redisChannel.startsWith("catapult:events:")
                ? redisChannel.substring("catapult:events:".length())
                : redisChannel;
        int colon = after.indexOf(':');
        return colon < 0 ? after : after.substring(0, colon);
    }
}
