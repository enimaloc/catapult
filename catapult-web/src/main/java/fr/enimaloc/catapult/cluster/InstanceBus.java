package fr.enimaloc.catapult.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What one catapult-web instance tells all of them (itself included), over Redis pub/sub on
 * {@value #CHANNEL}: a stream to close for a session that logged out, a cache to empty after an admin
 * change. Each message is a topic and a text payload, handed to that topic's handlers on every instance
 * (see {@link InstanceBusRedisBridge}). A lone instance (the mock profile, or Redis unreachable when
 * publishing) handles its own messages directly, so nothing is lost on it.
 */
@Slf4j
@Component
public class InstanceBus {
    public static final String CHANNEL = "catapult:web:instances";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();
    /** Null when this instance is alone: the mock profile, or a bus built for a test. */
    private final StringRedisTemplate redis;

    @Autowired
    public InstanceBus(ObjectProvider<StringRedisTemplate> redis, Environment environment) {
        this(environment.matchesProfiles("mock") ? null : redis.getIfAvailable());
    }

    InstanceBus(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** A bus for a lone instance, which handles its own messages at once. */
    public static InstanceBus local() {
        return new InstanceBus((StringRedisTemplate) null);
    }

    /** {@code handler} gets every {@code topic} message, from any instance. */
    public void on(String topic, Consumer<String> handler) {
        handlers.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** {@code payload} to {@code topic}'s handlers on every instance. */
    public void publish(String topic, String payload) {
        if (redis != null) {
            try {
                redis.convertAndSend(CHANNEL, JSON.writeValueAsString(Map.of("topic", topic, "payload", payload)));
                return;
            } catch (RuntimeException e) {
                log.warn("Redis unreachable, {} only reaches this instance: {}", topic, e.getMessage());
            }
        }
        dispatch(topic, payload);
    }

    /** A message read from {@value #CHANNEL}, as {@link #publish} wrote it. */
    void receive(String message) {
        try {
            JsonNode node = JSON.readTree(message);
            String topic = node.path("topic").asString(null);
            String payload = node.path("payload").asString(null);
            if (topic == null || payload == null) throw new IllegalArgumentException("no topic or payload");
            dispatch(topic, payload);
        } catch (RuntimeException e) {
            log.warn("Dropped a malformed instance message: {}", e.getMessage());
        }
    }

    private void dispatch(String topic, String payload) {
        for (Consumer<String> handler : handlers.getOrDefault(topic, List.of())) {
            try {
                handler.accept(payload);
            } catch (RuntimeException e) {
                log.error("Handling {} failed", topic, e);
            }
        }
    }
}
