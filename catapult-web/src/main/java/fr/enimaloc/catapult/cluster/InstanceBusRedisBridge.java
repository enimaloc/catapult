package fr.enimaloc.catapult.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/** Hands the {@link InstanceBus} messages any instance published to this one. Not in mock mode, alone. */
@Slf4j
@Configuration
@Profile("!mock")
public class InstanceBusRedisBridge {

    @Bean
    RedisMessageListenerContainer instanceBusListener(RedisConnectionFactory connectionFactory, InstanceBus bus) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Started by startInstanceBusListener, which survives an absent Redis.
        container.setAutoStartup(false);
        container.addMessageListener((message, pattern) -> bus.receive(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(InstanceBus.CHANNEL));
        return container;
    }

    /** A Redis that is down at boot costs the other instances' messages, not the whole web. */
    @Bean
    ApplicationRunner startInstanceBusListener(RedisMessageListenerContainer instanceBusListener) {
        return args -> {
            try {
                instanceBusListener.start();
            } catch (RuntimeException e) {
                log.error("Other instances' messages will not be received, Redis is unreachable", e);
            }
        };
    }
}
