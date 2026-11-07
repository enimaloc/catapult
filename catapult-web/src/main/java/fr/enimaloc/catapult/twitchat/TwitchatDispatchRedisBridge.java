package fr.enimaloc.catapult.twitchat;

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
 * Relays catapult-api's Twitchat dispatches (RedisEventPublisher's
 * catapult:events:twitchat:{accountId} channels, envelope {name, data, ts}) to the owner's open
 * pages. Not in mock mode, where MockApiService feeds the hub itself.
 */
@Slf4j
@Configuration
@Profile("!mock")
public class TwitchatDispatchRedisBridge {

    static final String PREFIX = "catapult:events:twitchat:";
    static final String DISPATCH = "twitchat.dispatch";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    RedisMessageListenerContainer twitchatDispatchListener(RedisConnectionFactory connectionFactory, TwitchatDispatchHub hub) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Started by startTwitchatDispatchListener, which survives an absent Redis.
        container.setAutoStartup(false);
        container.addMessageListener((message, pattern) -> forward(hub,
                new String(message.getChannel(), StandardCharsets.UTF_8),
                new String(message.getBody(), StandardCharsets.UTF_8)), new PatternTopic(PREFIX + "*"));
        return container;
    }

    /** A Redis that is down at boot costs the live Twitchat relay, not the whole web. */
    @Bean
    ApplicationRunner startTwitchatDispatchListener(RedisMessageListenerContainer twitchatDispatchListener) {
        return args -> {
            try {
                twitchatDispatchListener.start();
            } catch (RuntimeException e) {
                log.error("Twitchat dispatches will not be relayed, Redis is unreachable", e);
            }
        };
    }

    static void forward(TwitchatDispatchHub hub, String channel, String body) {
        if (!channel.startsWith(PREFIX)) return;
        try {
            UUID owner = UUID.fromString(channel.substring(PREFIX.length()));
            Map<?, ?> envelope = JSON.readValue(body, Map.class);
            if (!DISPATCH.equals(envelope.get("name"))) return;
            hub.publish(owner, JSON.writeValueAsString(envelope.get("data")));
        } catch (RuntimeException e) {
            log.warn("Dropped a malformed Twitchat event on {}: {}", channel, e.getMessage());
        }
    }
}
