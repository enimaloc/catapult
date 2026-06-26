package fr.enimaloc.catapult.web.ws;

import fr.enimaloc.catapult.web.ws.codec.msg.EventMessage;
import fr.enimaloc.catapult.web.ws.dispatch.ChannelResolver;
import fr.enimaloc.catapult.web.ws.metrics.WsMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subscribes to {@code catapult:events:*} on Redis and fans incoming envelopes out
 * to WebSocket sessions through {@link WsHub#broadcast(String, EventMessage)}.
 *
 * <p>The envelope produced by the API is {@code { "name": ..., "data": ..., "ts": ... }}.
 * The {@code data} field is left as a {@link JsonNode} — Jackson re-serializes it correctly
 * when the outgoing {@link EventMessage} (whose {@code data} is typed {@code Object}) is encoded.</p>
 */
@Slf4j
@Configuration
public class RedisEventSubscriber {

    public static final String PATTERN = "catapult:events:*";

    private final WsHub hub;
    private final ChannelResolver channelResolver;
    private final WsSessionRegistry sessionRegistry;
    private final WsMetrics metrics;
    private final ObjectMapper jackson;

    @Autowired
    public RedisEventSubscriber(WsHub hub,
                                ChannelResolver channelResolver,
                                WsSessionRegistry sessionRegistry,
                                WsMetrics metrics,
                                @Autowired(required = false) ObjectMapper jackson) {
        this.hub = hub;
        this.channelResolver = channelResolver;
        this.sessionRegistry = sessionRegistry;
        this.metrics = metrics;
        this.jackson = jackson != null ? jackson : JsonMapper.builder().build();
    }

    @Bean
    public RedisMessageListenerContainer redisEventListenerContainer(RedisConnectionFactory factory) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(this::onMessage, new PatternTopic(PATTERN));
        return container;
    }

    void onMessage(Message message, byte[] pattern) {
        onMessage(message);
    }

    void onMessage(Message message) {
        String redisChannel = new String(message.getChannel());
        String internalChannel = channelResolver.resolveRedisToInternal(redisChannel);
        if (internalChannel == null) {
            log.debug("ignoring redis message on unmapped channel {}", redisChannel);
            return;
        }
        JsonNode envelope;
        try {
            envelope = jackson.readTree(message.getBody());
        } catch (Exception e) {
            log.warn("invalid envelope on {}: {}", redisChannel, e.getMessage());
            return;
        }
        String name = envelope.path("name").asString(null);
        if (name == null || name.isBlank()) {
            log.warn("envelope on {} missing 'name': {}", redisChannel, envelope);
            return;
        }
        JsonNode data = envelope.path("data");
        String publicChannel = toPublicChannel(internalChannel);
        log.debug("redis→ws fanout: redis={} internal={} public={} name={}",
                redisChannel, internalChannel, publicChannel, name);
        metrics.recordRedisEventReceived(redisChannel);
        int fanoutCount = sessionRegistry.subscribersOf(internalChannel).size();
        hub.broadcast(internalChannel, new EventMessage(publicChannel, name, data));
        metrics.recordFanoutTargets(redisChannel, fanoutCount);
    }

    /**
     * Internal channels carry per-user UUIDs (e.g. {@code notifications.user.<uuid>}).
     * The outgoing WS event surfaces only the public-facing channel name the client
     * subscribed to (e.g. {@code notifications.user}).
     */
    private String toPublicChannel(String internalChannel) {
        if (internalChannel.startsWith("notifications.user.")) {
            return "notifications.user";
        }
        return internalChannel;
    }
}
