package fr.enimaloc.catapult.web.ws.dispatch;

import fr.enimaloc.catapult.web.ws.WsSession;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ChannelResolver {

    private static final String REDIS_USER_PREFIX = "catapult:events:user:";

    public Optional<String> resolvePublicToInternal(String publicName, WsSession session) {
        return switch (publicName) {
            case "events.global" -> Optional.of("events.global");
            case "events.admin" -> session.userId().isPresent() && session.roles().contains("ADMIN")
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
        return null;
    }
}
