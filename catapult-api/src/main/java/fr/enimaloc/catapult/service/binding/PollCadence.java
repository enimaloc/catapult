package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.premium.PremiumService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How often each account is polled: premium accounts every {@value #PREMIUM_KEY} seconds, the
 * others every {@value #FREE_KEY}. The loop ticks at the GCD of both, so each tier lands on a
 * tick; an account is due when its interval has elapsed, give or take half a tick so a tick
 * firing slightly early does not push it back a whole tick. Intervals are read from the
 * environment on each call, so an admin override applies without a restart.
 */
@Slf4j
@Component
public class PollCadence {
    public static final String PREMIUM_KEY = "app.polling.premium-interval-seconds";
    public static final String FREE_KEY = "app.polling.free-interval-seconds";
    private static final long DEFAULT_PREMIUM_SECONDS = 30;
    private static final long DEFAULT_FREE_SECONDS = 60;

    private final Environment env;
    private final PremiumService premiumService;
    // Account -> start of the last cycle that polled it; in memory, so everyone is due after a restart
    private final Map<UUID, Instant> lastPolled = new ConcurrentHashMap<>();
    // Unreadable values already reported, so a bad override is logged once rather than on every check
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public PollCadence(Environment env, PremiumService premiumService) {
        this.env = env;
        this.premiumService = premiumService;
    }

    /** Seconds between two runs of the loop: the GCD of both intervals. */
    public long tickSeconds() {
        long premium = premiumSeconds();
        long free = freeSeconds();
        while (free != 0) {
            long rest = premium % free;
            premium = free;
            free = rest;
        }
        return premium;
    }

    /** True when the account was never polled, or its tier's interval has elapsed (half a tick early still counts). */
    public boolean isDue(UserAccount user, Instant now) {
        Instant last = lastPolled.get(user.getId());
        if (last == null) {
            return true;
        }
        long intervalMs = (premiumService.isPremium(user) ? premiumSeconds() : freeSeconds()) * 1000;
        long toleranceMs = tickSeconds() * 1000 / 2;
        return Duration.between(last, now).toMillis() >= intervalMs - toleranceMs;
    }

    public void markPolled(UUID userId, Instant at) {
        lastPolled.put(userId, at);
    }

    /** Seconds between two polls of a premium account, as the loop applies them. */
    public long premiumSeconds() {
        return interval(PREMIUM_KEY, DEFAULT_PREMIUM_SECONDS);
    }

    /** Seconds between two polls of a free account, as the loop applies them. */
    public long freeSeconds() {
        return interval(FREE_KEY, DEFAULT_FREE_SECONDS);
    }

    /** The configured interval, at least 1 s; the default when the value is not a number. */
    private long interval(String key, long fallback) {
        return parseSeconds(key, env.getProperty(key), fallback, reported);
    }

    /**
     * Reads an interval the way the loop does: at least 1 s, the fallback (logged once per
     * distinct value) when the raw value is not a number, and the fallback when it is absent.
     */
    public static long parseSeconds(String key, String raw, long fallback, Set<String> reported) {
        if (raw == null) {
            return Math.max(1, fallback);
        }
        try {
            return Math.max(1, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            if (reported.add(key + '=' + raw)) {
                log.warn("Unreadable {} '{}', using {}s", key, raw, fallback);
            }
            return Math.max(1, fallback);
        }
    }

    /** The built-in default of one of the two keys. */
    public static long defaultSeconds(String key) {
        return PREMIUM_KEY.equals(key) ? DEFAULT_PREMIUM_SECONDS : DEFAULT_FREE_SECONDS;
    }
}
