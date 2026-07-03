package fr.enimaloc.catapult.service.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Typed convenience layer over {@link RedisEventPublisher#publishAdmin} for
 * admin-scoped domain events. Broadcast on {@code catapult:events:admin},
 * resolved by catapult-web to the public {@code events.admin} channel which is
 * gated to ROLE_ADMIN sessions.
 *
 * <p>Payloads never carry secrets: key events transport the already-masked
 * status view, never the raw API key.</p>
 */
@Component
@RequiredArgsConstructor
public class AdminEventPublisher {

    public static final String PROVIDER_STEAM = "steam";
    public static final String PROVIDER_DTDD = "dtdd";

    private final RedisEventPublisher redisPublisher;

    /** A key was added. {@code keyStatus} is the masked status view (no raw key). */
    public void keyAdded(String provider, Object keyStatus) {
        redisPublisher.publishAdmin(provider + ".key.added", Map.of("key", keyStatus));
    }

    /** A key was deleted, identified by its opaque hashed id. */
    public void keyDeleted(String provider, String keyId) {
        redisPublisher.publishAdmin(provider + ".key.deleted", Map.of("keyId", keyId));
    }

    /** The rotator refreshed; {@code keys} is the full masked status list. */
    public void keysRefreshed(String provider, List<?> keys) {
        redisPublisher.publishAdmin(provider + ".keys.refreshed", Map.of("keys", keys));
    }
}
