package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.service.metrics.CatapultApiMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes events to Redis pub/sub channels consumed by catapult-web's
 * {@code RedisEventSubscriber} and fanned out to WebSocket sessions.
 *
 * Channels:
 * <ul>
 *   <li>{@code catapult:events:global} — broadcast to every connected client</li>
 *   <li>{@code catapult:events:user:{uuid}} — targeted to a single user's sessions</li>
 *   <li>{@code catapult:events:admin} — broadcast restricted to admin role</li>
 * </ul>
 */
@Slf4j
@Component
public class RedisEventPublisher {

    public static final String CHANNEL_GLOBAL = "catapult:events:global";
    public static final String CHANNEL_ADMIN  = "catapult:events:admin";
    public static final String CHANNEL_USER_PREFIX = "catapult:events:user:";
    public static final String CHANNEL_CHANNEL_PREFIX = "catapult:events:channel:";

    private final StringRedisTemplate redis;
    private final ObjectMapper jackson;

    @Autowired(required = false)
    private CatapultApiMetrics apiMetrics;

    public RedisEventPublisher(StringRedisTemplate redis) {
        this(redis, JsonMapper.builder().build());
    }

    @Autowired(required = false)
    public RedisEventPublisher(StringRedisTemplate redis, ObjectMapper jackson) {
        this.redis = redis;
        this.jackson = jackson != null ? jackson : JsonMapper.builder().build();
    }

    public void publishGlobal(String name, Object data) {
        publish(CHANNEL_GLOBAL, name, data);
    }

    public void publishUser(UUID userId, String name, Object data) {
        publish(CHANNEL_USER_PREFIX + userId, name, data);
    }

    public void publishAdmin(String name, Object data) {
        publish(CHANNEL_ADMIN, name, data);
    }

    public void publishChannel(UUID channelOwnerId, String name, Object data) {
        publish(CHANNEL_CHANNEL_PREFIX + channelOwnerId, name, data);
    }

    private void publish(String channel, String name, Object data) {
        try {
            // LinkedHashMap so the JSON keeps a stable order: {name, data, ts}
            Map<String, Object> envelope = new LinkedHashMap<>(3);
            envelope.put("name", name);
            envelope.put("data", data);
            envelope.put("ts", System.currentTimeMillis());
            String json = jackson.writeValueAsString(envelope);
            redis.convertAndSend(channel, json);
            if (apiMetrics != null) apiMetrics.recordRedisPublished(channel);
        } catch (Exception e) {
            log.error("Failed to publish event '{}' to {}: {}", name, channel, e.getMessage(), e);
        }
    }
}
