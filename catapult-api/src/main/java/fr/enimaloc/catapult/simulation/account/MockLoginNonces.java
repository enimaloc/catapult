package fr.enimaloc.catapult.simulation.account;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fictitious login's CSRF guard: the choice page carries a nonce of its own, and the login only goes through with
 * it, once and for {@link #TTL}. A page of another site cannot read one, so it cannot sign a visitor in as a
 * fictitious account.
 */
@Component
@Profile("mock")
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class MockLoginNonces {

    static final Duration TTL = Duration.ofMinutes(5);
    private static final int BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> expiries = new ConcurrentHashMap<>();
    private final Clock clock;

    public MockLoginNonces() {
        this(Clock.systemUTC());
    }

    MockLoginNonces(Clock clock) {
        this.clock = clock;
    }

    /** A new nonce, valid once for {@link #TTL}. */
    public String issue() {
        Instant now = clock.instant();
        expiries.values().removeIf(expiry -> expiry.isBefore(now));
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        expiries.put(nonce, now.plus(TTL));
        return nonce;
    }

    /** Whether {@code nonce} was issued, has not expired and was not used yet; it is used up either way. */
    public boolean consume(String nonce) {
        if (nonce == null) {
            return false;
        }
        Instant expiry = expiries.remove(nonce);
        return expiry != null && !expiry.isBefore(clock.instant());
    }
}
