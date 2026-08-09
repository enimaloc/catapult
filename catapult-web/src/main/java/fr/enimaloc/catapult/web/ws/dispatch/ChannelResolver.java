package fr.enimaloc.catapult.web.ws.dispatch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChannelResolver {

    private static final String REDIS_USER_PREFIX = "catapult:events:user:";
    private static final String REDIS_CHANNEL_PREFIX = "catapult:events:channel:";
    private static final String PUBLIC_CHANNEL_VIEWED_PREFIX = "channel.viewed.";
    private static final String PUBLIC_TWITCHAT_WIDGET_PREFIX = "twitchat.widget.";
    private static final String REDIS_TWITCHAT_PREFIX = "catapult:events:twitchat:";

    private final ApiClient apiClient;

    public Optional<String> resolvePublicToInternal(String publicName, WsSession session) {
        if (publicName == null) return Optional.empty();
        if (publicName.startsWith(PUBLIC_CHANNEL_VIEWED_PREFIX)) {
            return resolveChannelViewed(publicName.substring(PUBLIC_CHANNEL_VIEWED_PREFIX.length()), session);
        }
        if (publicName.startsWith(PUBLIC_TWITCHAT_WIDGET_PREFIX)) {
            return resolveTwitchatWidget(publicName.substring(PUBLIC_TWITCHAT_WIDGET_PREFIX.length()));
        }
        return switch (publicName) {
            case "events.global" -> Optional.of("events.global");
            case "events.admin" -> session.userId().isPresent() && session.roles().contains("ROLE_ADMIN")
                    ? Optional.of("events.admin")
                    : Optional.empty();
            case "notifications.user" -> session.userId().map(uuid -> "notifications.user." + uuid);
            default -> Optional.empty();
        };
    }

    public String resolveRedisToInternal(String redisChannel) {
        if (redisChannel == null) return null;
        if (redisChannel.equals("catapult:events:global")) return "events.global";
        if (redisChannel.equals("catapult:events:admin")) return "events.admin";
        if (redisChannel.startsWith(REDIS_USER_PREFIX)) {
            return "notifications.user." + redisChannel.substring(REDIS_USER_PREFIX.length());
        }
        if (redisChannel.startsWith(REDIS_CHANNEL_PREFIX)) {
            return PUBLIC_CHANNEL_VIEWED_PREFIX + redisChannel.substring(REDIS_CHANNEL_PREFIX.length());
        }
        if (redisChannel.startsWith(REDIS_TWITCHAT_PREFIX)) {
            return redisChannel;
        }
        return null;
    }

    /**
     * Resolves a {@code channel.viewed.<ownerUuid>} subscription. Requires:
     *  - the suffix is a parseable UUID,
     *  - the WS session is authenticated,
     *  - the upstream API confirms the viewer can access the channel
     *    (delegated to {@link ChannelAccessService#canAccess} on the API side).
     *
     * Any failure returns empty, which the hub translates into {@code sub.denied}.
     * The internal channel name mirrors the public form so {@link #resolveRedisToInternal}
     * can fan events back out using the same key.
     */
    private Optional<String> resolveChannelViewed(String suffix, WsSession session) {
        if (session.userId().isEmpty()) return Optional.empty();
        UUID ownerUuid;
        try {
            ownerUuid = UUID.fromString(suffix);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        AccessResponse access = apiClient.get(
                "/api/users/{ownerId}/channel-access", AccessResponse.class, ownerUuid.toString());
        if (access == null || !access.accessible()) return Optional.empty();
        return Optional.of(PUBLIC_CHANNEL_VIEWED_PREFIX + ownerUuid);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessResponse(boolean accessible) {}

    /**
     * Resolves a {@code twitchat.widget.<token>} subscription by asking catapult-api
     * whether the token is known and its owner has Twitchat notifications enabled.
     * The internal channel name is the redis channel name itself (no rename), since
     * broadcaster events are published straight to {@code catapult:events:twitchat:<ownerId>}.
     */
    private Optional<String> resolveTwitchatWidget(String token) {
        TwitchatAccessResponse access = apiClient.get(
                "/api/twitchat/widget/{token}/access", TwitchatAccessResponse.class, token);
        if (access == null || !access.enabled()) return Optional.empty();
        return Optional.of(REDIS_TWITCHAT_PREFIX + access.ownerId());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TwitchatAccessResponse(String ownerId, boolean enabled) {}
}
