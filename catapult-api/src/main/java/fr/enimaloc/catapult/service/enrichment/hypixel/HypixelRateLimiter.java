package fr.enimaloc.catapult.service.enrichment.hypixel;

import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbound limiter of the Hypixel and Mojang calls, in memory: one bucket per Hypixel key (Hypixel counts its limits
 * per key), one for the keyless games list and one for Mojang. Each bucket has a spacing between two calls that
 * waits (the slot is reserved under the bucket's lock and the wait happens outside it; a slot more than
 * {@link #MAX_WAIT} away is refused) and a sliding window that refuses. A 429, or a {@code RateLimit-Remaining: 0}
 * answer, {@linkplain #pause pauses} the bucket. Not shared with the presence detection.
 */
@Component
public class HypixelRateLimiter {

    /** The longest wait for a slot; beyond it the call is refused. */
    static final Duration MAX_WAIT = Duration.ofSeconds(1);
    /** The bucket of Mojang's profile lookup. */
    public static final String MOJANG = "mojang";
    /** The bucket of the keyless Hypixel routes (the games list). */
    public static final String PUBLIC = "hypixel-public";

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** One key (or the keyless routes, or Mojang). Guarded by its own monitor. */
    private static final class Bucket {
        final Duration spacing;
        final int cap;
        final Duration window;
        final Deque<Instant> calls = new ArrayDeque<>();
        Instant last = Instant.MIN;
        Instant pausedUntil = Instant.MIN;

        Bucket(Duration spacing, int cap, Duration window) {
            this.spacing = spacing;
            this.cap = cap;
            this.window = window;
        }
    }

    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration keySpacing;
    private final int keyCap;
    private final int mojangCap;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public HypixelRateLimiter(EnrichmentProperties properties) {
        this(properties, Clock.systemUTC(), Thread::sleep);
    }

    HypixelRateLimiter(EnrichmentProperties properties, Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
        EnrichmentProperties.Hypixel hypixel = properties.getHypixel();
        this.keySpacing = Duration.ofNanos(1_000_000_000L / Math.max(1, hypixel.getPerSecond()));
        this.keyCap = Math.max(1, hypixel.getPerFiveMinutes());
        this.mojangCap = Math.max(1, hypixel.getMojangPerMinute());
    }

    private Bucket bucket(String name) {
        return buckets.computeIfAbsent(name, n -> switch (n) {
            case MOJANG -> new Bucket(Duration.ofMillis(250), mojangCap, Duration.ofMinutes(1));
            case PUBLIC -> new Bucket(Duration.ofMillis(250), 60, Duration.ofMinutes(5));
            default -> new Bucket(keySpacing, keyCap, Duration.ofMinutes(5));
        });
    }

    /** Takes a slot of {@code bucket}, waiting up to {@link #MAX_WAIT}; false when refused. */
    public boolean acquire(String bucket) {
        Duration wait = reserve(bucket(bucket));
        if (wait == null) {
            return false;
        }
        if (!wait.isZero()) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private Duration reserve(Bucket b) {
        synchronized (b) {
            Instant now = clock.instant();
            if (now.isBefore(b.pausedUntil)) {
                return null;
            }
            Instant limit = now.minus(b.window);
            while (!b.calls.isEmpty() && !b.calls.peekFirst().isAfter(limit)) {
                b.calls.removeFirst();
            }
            if (b.calls.size() >= b.cap) {
                return null;
            }
            Instant slot = b.last.plus(b.spacing).isAfter(now) ? b.last.plus(b.spacing) : now;
            Duration wait = Duration.between(now, slot);
            if (wait.compareTo(MAX_WAIT) > 0) {
                return null;
            }
            b.last = slot;
            b.calls.addLast(slot);
            return wait;
        }
    }

    /** Refuses every call of {@code bucket} for {@code duration} (a 429, or the key's quota spent). */
    public void pause(String bucket, Duration duration) {
        Bucket b = bucket(bucket);
        synchronized (b) {
            Instant until = clock.instant().plus(duration);
            if (until.isAfter(b.pausedUntil)) {
                b.pausedUntil = until;
            }
        }
    }

    public boolean paused(String bucket) {
        Bucket b = bucket(bucket);
        synchronized (b) {
            return clock.instant().isBefore(b.pausedUntil);
        }
    }
}
