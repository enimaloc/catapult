package fr.enimaloc.catapult.twitchat;

import fr.enimaloc.catapult.common.event.ChannelEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Relays catapult-api's {@link ChannelEvents.NotificationDeliveryPending} (envelope {name, data, ts}, on its own
 * Redis channel: it names many members at once, so no per-channel pattern catches it) to the hub, which flushes
 * for those of them whose Twitchat page or widget is open on this instance. Not in mock mode.
 */
@Slf4j
@Configuration
@Profile("!mock")
public class NotificationDeliveryRedisBridge {

    static final String CHANNEL = ChannelEvents.NOTIFICATION_DELIVERY_REDIS_CHANNEL;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    RedisMessageListenerContainer notificationDeliveryListener(RedisConnectionFactory connectionFactory, TwitchatDispatchHub hub) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Started by startNotificationDeliveryListener, which survives an absent Redis.
        container.setAutoStartup(false);
        container.addMessageListener((message, pattern) -> forward(hub,
                new String(message.getChannel(), StandardCharsets.UTF_8),
                new String(message.getBody(), StandardCharsets.UTF_8)), new ChannelTopic(CHANNEL));
        return container;
    }

    /** A Redis that is down at boot costs the Twitchat notification deliveries, not the whole web. */
    @Bean
    ApplicationRunner startNotificationDeliveryListener(RedisMessageListenerContainer notificationDeliveryListener) {
        return args -> {
            try {
                notificationDeliveryListener.start();
            } catch (RuntimeException e) {
                log.error("Twitchat notification deliveries will not be triggered, Redis is unreachable", e);
            }
        };
    }

    static void forward(TwitchatDispatchHub hub, String channel, String body) {
        if (!CHANNEL.equals(channel)) return;
        try {
            JsonNode envelope = JSON.readTree(body);
            if (!ChannelEvents.NOTIFICATION_DELIVERY_PENDING.equals(envelope.path("name").asString(null))) return;
            JsonNode userIds = envelope.path("data").path("userIds");
            if (!userIds.isArray()) return;
            for (JsonNode id : userIds) {
                try {
                    hub.flush(UUID.fromString(id.asString("")));
                } catch (IllegalArgumentException e) {
                    log.warn("Dropped a malformed member id in a notification delivery event: {}", id);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Dropped a malformed notification delivery event: {}", e.getMessage());
        }
    }
}
