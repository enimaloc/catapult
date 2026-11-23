package fr.enimaloc.catapult.service.enrichment.supercell;

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
 * Outbound limiter of the Supercell calls, in memory: one bucket per key (each key belongs to one game; Supercell
 * throttles per key). Each bucket has a spacing between two calls that waits (the slot is reserved under the
 * bucket's lock and the wait happens outside it; a slot more than {@link #MAX_WAIT} away is refused) and a one-minute
 * sliding window that refuses. A 429 {@linkplain #pause pauses} the bucket. Not shared with the presence detection.
 */
@Component
public class SupercellRateLimiter {

    /** The longest wait for a slot; beyond it the call is refused. */
    static final Duration MAX_WAIT = Duration.ofSeconds(1);
    static final Duration WINDOW = Duration.ofMinutes(1);

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** One key. Guarded by its own monitor. */
    private static final class Bucket {
        final Deque<Instant> calls = new ArrayDeque<>();
        Instant last = Instant.MIN;
        Instant pausedUntil = Instant.MIN;
    }

    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration spacing;
    private final int cap;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public SupercellRateLimiter(EnrichmentProperties properties) {
        this(properties, Clock.systemUTC(), Thread::sleep);
    }

    SupercellRateLimiter(EnrichmentProperties properties, Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
        EnrichmentProperties.Supercell supercell = properties.getSupercell();
        this.spacing = Duration.ofNanos(1_000_000_000L / Math.max(1, supercell.getPerSecond()));
        this.cap = Math.max(1, supercell.getPerMinute());
    }

    private Bucket bucket(String name) {
        return buckets.computeIfAbsent(name, n -> new Bucket());
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
            Instant limit = now.minus(WINDOW);
            while (!b.calls.isEmpty() && !b.calls.peekFirst().isAfter(limit)) {
                b.calls.removeFirst();
            }
            if (b.calls.size() >= cap) {
                return null;
            }
            Instant slot = b.last.plus(spacing).isAfter(now) ? b.last.plus(spacing) : now;
            Duration wait = Duration.between(now, slot);
            if (wait.compareTo(MAX_WAIT) > 0) {
                return null;
            }
            b.last = slot;
            b.calls.addLast(slot);
            return wait;
        }
    }

    /** Refuses every call of {@code bucket} for {@code duration} (Supercell answered 429). */
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
