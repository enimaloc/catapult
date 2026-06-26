package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.binding.BindingDto;
import fr.enimaloc.catapult.service.settings.UserSettingsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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
 * with the channel name, but useful for client-side debug and when the same
 * event reaches multiple channels in the future.</p>
 */
@Component
@RequiredArgsConstructor
public class ChannelEventPublisher {

    private final RedisEventPublisher redisPublisher;

    public void botToggled(UUID channelOwnerId, boolean enabled) {
        publish(channelOwnerId, "bot.toggled", Map.of("enabled", enabled));
    }

    public void streamStateChanged(UUID channelOwnerId, boolean isLive) {
        publish(channelOwnerId, "stream.state.changed", Map.of("isLive", isLive));
    }

    public void gameDetected(UUID channelOwnerId, DetectedGame game) {
        Map<String, Object> data = new HashMap<>();
        data.put("sourceName", game == null ? null : game.getSourceName());
        data.put("sourceType", game == null || game.getSourceType() == null
                ? null : game.getSourceType().name());
        publish(channelOwnerId, "game.detected", data);
    }

    public void gameCleared(UUID channelOwnerId) {
        publish(channelOwnerId, "game.cleared", Map.of());
    }

    public void bindingUpserted(UUID channelOwnerId, BindingDto binding) {
        publish(channelOwnerId, "binding.upserted", Map.of("binding", binding));
    }

    public void bindingDeleted(UUID channelOwnerId, UUID bindingId) {
        publish(channelOwnerId, "binding.deleted", Map.of("bindingId", bindingId.toString()));
    }

    public void settingsUpdated(UUID channelOwnerId, UserSettingsDto settings) {
        publish(channelOwnerId, "settings.updated", Map.of("settings", settings));
    }

    public void steamProfileChanged(UUID channelOwnerId) {
        publish(channelOwnerId, "steam.profile.changed", Map.of());
    }

    public void dtddMappingChanged(UUID channelOwnerId) {
        publish(channelOwnerId, "dtdd.mapping.changed", Map.of());
    }

    public void connectionChanged(UUID channelOwnerId, String provider, boolean connected) {
        publish(channelOwnerId, "connection.changed",
                Map.of("provider", provider, "connected", connected));
    }

    private void publish(UUID channelOwnerId, String name, Map<String, Object> data) {
        // LinkedHashMap so channelId is the first field on the wire — easier
        // to spot when scrolling a trace.
        Map<String, Object> payload = new LinkedHashMap<>(data.size() + 1);
        payload.put("channelId", channelOwnerId.toString());
        payload.putAll(data);
        redisPublisher.publishChannel(channelOwnerId, name, payload);
    }
}
