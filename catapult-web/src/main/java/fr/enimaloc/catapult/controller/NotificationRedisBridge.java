package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.event.notification.BroadcastStreamEvent;
import fr.enimaloc.catapult.event.notification.NotificationStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Relays catapult-api's notification events (RedisNotificationPusher, envelope {name, data, ts}) as
 * application events for NavEventStreamController: an account's, on catapult:events:user:{accountId},
 * as a {@link NotificationStreamEvent} under its SSE name; the broadcasts, on catapult:events:global,
 * as a {@link BroadcastStreamEvent} when they are one of {@link #BROADCASTS}. Not in mock mode, where
 * MockNotificationStore publishes them itself.
 */
@Slf4j
@Configuration
@Profile("!mock")
public class NotificationRedisBridge {

    static final String GLOBAL = "catapult:events:global";
    static final String USER_PREFIX = "catapult:events:user:";
    static final Set<String> BROADCASTS = Set.of("maintenance.scheduled", "maintenance.cancelled",
            "version.deployed", "alert.info", "alert.warning", "maintenance.imminent");
    /** catapult-api's notification event names, and the SSE names they go by. */
    static final Map<String, String> NOTIFICATIONS = Map.of(
            "notification.created", NotificationStreamEvent.CREATED,
            "notification.read.changed", NotificationStreamEvent.READ,
            "notification.all.read", NotificationStreamEvent.ALL_READ,
            "notification.deleted", NotificationStreamEvent.DELETED);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    RedisMessageListenerContainer notificationEventListener(RedisConnectionFactory connectionFactory,
                                                            ApplicationEventPublisher events) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Started by startNotificationEventListener, which survives an absent Redis.
        container.setAutoStartup(false);
        container.addMessageListener((message, pattern) -> forward(events,
                new String(message.getChannel(), StandardCharsets.UTF_8),
                new String(message.getBody(), StandardCharsets.UTF_8)),
                List.of(new ChannelTopic(GLOBAL), new PatternTopic(USER_PREFIX + "*")));
        return container;
    }

    /** A Redis that is down at boot costs the live notifications and broadcasts, not the whole web. */
    @Bean
    ApplicationRunner startNotificationEventListener(RedisMessageListenerContainer notificationEventListener) {
        return args -> {
            try {
                notificationEventListener.start();
            } catch (RuntimeException e) {
                log.error("Notifications and broadcasts will not be relayed, Redis is unreachable", e);
            }
        };
    }

    @SuppressWarnings("unchecked")
    static void forward(ApplicationEventPublisher events, String channel, String body) {
        try {
            if (GLOBAL.equals(channel)) {
                Map<?, ?> envelope = JSON.readValue(body, Map.class);
                Object name = envelope.get("name");
                if (!(name instanceof String broadcast) || !BROADCASTS.contains(broadcast)) return;
                Object data = envelope.get("data");
                if (data != null && !(data instanceof Map)) return;
                events.publishEvent(new BroadcastStreamEvent(broadcast,
                        data == null ? Map.of() : (Map<String, Object>) data));
            } else if (channel.startsWith(USER_PREFIX)) {
                UUID account = UUID.fromString(channel.substring(USER_PREFIX.length()));
                Map<?, ?> envelope = JSON.readValue(body, Map.class);
                String name = envelope.get("name") instanceof String n ? NOTIFICATIONS.get(n) : null;
                Object data = envelope.get("data");
                if (name == null || data == null) return;
                events.publishEvent(new NotificationStreamEvent(account, name, data));
            }
        } catch (RuntimeException e) {
            log.warn("Dropped a malformed notification event on {}: {}", channel, e.getMessage());
        }
    }
}
