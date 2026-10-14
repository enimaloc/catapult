package fr.enimaloc.catapult.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Relays catapult-api's channel events (ChannelEventPublisher's catapult:events:channel:{ownerId}
 * channels, envelope {name, data, ts}) to the navbars listing that channel; for now only
 * {@code stream.state.changed}. Not in mock mode, where MockAdminController publishes them itself.
 */
@Slf4j
@Configuration
@Profile("!mock")
public class ChannelEventRedisBridge {

    static final String PREFIX = "catapult:events:channel:";
    static final String STREAM_STATE = "stream.state.changed";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    RedisMessageListenerContainer channelEventListener(RedisConnectionFactory connectionFactory,
                                                       NavEventStreamController navEvents) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Started by startChannelEventListener, which survives an absent Redis.
        container.setAutoStartup(false);
        container.addMessageListener((message, pattern) -> forward(navEvents,
                new String(message.getChannel(), StandardCharsets.UTF_8),
                new String(message.getBody(), StandardCharsets.UTF_8)), new PatternTopic(PREFIX + "*"));
        return container;
    }

    /** A Redis that is down at boot costs the navbar's live states, not the whole web. */
    @Bean
    ApplicationRunner startChannelEventListener(RedisMessageListenerContainer channelEventListener) {
        return args -> {
            try {
                channelEventListener.start();
            } catch (RuntimeException e) {
                log.error("Channel events will not be relayed, Redis is unreachable", e);
            }
        };
    }

    static void forward(NavEventStreamController navEvents, String channel, String body) {
        if (!channel.startsWith(PREFIX)) return;
        try {
            UUID owner = UUID.fromString(channel.substring(PREFIX.length()));
            Map<?, ?> envelope = JSON.readValue(body, Map.class);
            if (!STREAM_STATE.equals(envelope.get("name"))) return;
            Map<?, ?> data = (Map<?, ?>) envelope.get("data");
            navEvents.liveChanged(owner, Boolean.TRUE.equals(data.get("isLive")));
        } catch (RuntimeException e) {
            log.warn("Dropped a malformed channel event on {}: {}", channel, e.getMessage());
        }
    }
}
