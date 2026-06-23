package fr.enimaloc.catapult.web.ws.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Short-lived one-shot tickets used to upgrade an unauthenticated WebSocket
 * session to an authenticated one.
 *
 * <p>A ticket is 32 bytes of {@link SecureRandom} entropy (256 bits) encoded as
 * URL-safe base64. It is stored in a Caffeine cache with TTL 10s and a hard
 * upper bound of 10 000 entries to cap memory.</p>
 *
 * <p>{@link #consume(String)} atomically returns the snapshot and invalidates
 * the entry so a replay of the same ticket yields {@link Optional#empty()}.</p>
 */
@Component
public class WsTicketStore {

    /** Information the WebSocket session needs after auth.ok. */
    public record AuthSnapshot(UUID userId, Set<String> roles, String jwt) {
        public AuthSnapshot {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }

        /** Convenience constructor for tests / callers that don't carry a JWT yet. */
        public AuthSnapshot(UUID userId, Set<String> roles) {
            this(userId, roles, null);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public static final Duration TTL = Duration.ofSeconds(10);
    public static final long MAX_ENTRIES = 10_000L;

    private final Cache<String, AuthSnapshot> cache;

    public WsTicketStore() {
        this(Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_ENTRIES)
                .build());
    }

    /** Visible for tests that want to inject a fake {@link Ticker}. */
    WsTicketStore(Cache<String, AuthSnapshot> cache) {
        this.cache = cache;
    }

    /** Returns a new ticket bound to the given identity. */
    public String issue(UUID userId, Set<String> roles) {
        return issue(userId, roles, null);
    }

    /**
     * Returns a new ticket bound to the given identity and JWT. The JWT is
     * stashed so the WS dispatcher can propagate it to {@code ApiClient} when
     * forwarding handler calls to upstream REST endpoints.
     */
    public String issue(UUID userId, Set<String> roles, String jwt) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = ENCODER.encodeToString(bytes);
        cache.put(token, new AuthSnapshot(userId, roles == null ? Set.of() : roles, jwt));
        return token;
    }

    /**
     * Atomically reads and invalidates a ticket. A subsequent call with the
     * same token returns {@link Optional#empty()}.
     */
    public Optional<AuthSnapshot> consume(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        // ConcurrentMap.remove gives us the get+invalidate atomicity we need
        // (Cache.getIfPresent + Cache.invalidate would be racy).
        AuthSnapshot snapshot = cache.asMap().remove(token);
        return Optional.ofNullable(snapshot);
    }
}
