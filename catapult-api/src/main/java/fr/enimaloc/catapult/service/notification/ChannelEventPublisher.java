package fr.enimaloc.catapult.service.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Typed convenience layer over {@link RedisEventPublisher} for per-channel
 * domain events. Each domain method names the event explicitly and shapes the
 * payload so callers do not have to remember the wire contract.
 *
 * <p>Channel: {@code catapult:events:channel:<ownerId>}. Resolved by
 * catapult-web's {@code ChannelResolver} from the public subscription name
 * {@code channel.viewed.<ownerId>} (gated by {@code canAccess}).</p>
 *
 * <p>Every payload carries {@code channelId} as a top-level field — redundant
 * with the channel name, but useful when the same event reaches multiple
 * channels in the future and the client needs a single discriminator.</p>
 */
@Component
@RequiredArgsConstructor
public class ChannelEventPublisher {

    private final RedisEventPublisher redisPublisher;

    /** Bot enable flag changed for the owner. */
    public void botToggled(UUID channelOwnerId, boolean enabled) {
        publish(channelOwnerId, "bot.toggled", Map.of("enabled", enabled));
    }

    private void publish(UUID channelOwnerId, String name, Map<String, Object> data) {
        // LinkedHashMap so channelId is the first field on the wire — easier to
        // spot when scrolling a trace.
        Map<String, Object> payload = new LinkedHashMap<>(data.size() + 1);
        payload.put("channelId", channelOwnerId.toString());
        payload.putAll(data);
        redisPublisher.publishChannel(channelOwnerId, name, payload);
    }
}
