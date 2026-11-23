package fr.enimaloc.catapult.service.enrichment.faceit;

import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Outbound limiter of the FACEIT Data API calls, in memory: a sliding one-hour window that refuses, and a spacing of
 * {@code 1 / per-second} between two calls that waits. The slot is reserved under the lock and the wait happens
 * outside it; a slot more than {@link #MAX_WAIT} away is refused. A 429 answer {@linkplain #pause pauses} every call.
 * FACEIT is only an enrichment source: nothing else shares this limiter.
 */
@Component
public class FaceitRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    /** The longest wait for a slot; beyond it the call is refused. */
    static final Duration MAX_WAIT = Duration.ofSeconds(1);

    /** Waits; {@link Thread#sleep(Duration)} outside of tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Clock clock;
    private final Sleeper sleeper;
    private final int cap;
    private final Duration spacing;
    private final Deque<Instant> calls = new ArrayDeque<>();
    private Instant pausedUntil = Instant.MIN;

    @Autowired
    public FaceitRateLimiter(EnrichmentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    FaceitRateLimiter(EnrichmentProperties properties, Clock clock) {
        this(properties, clock, Thread::sleep);
    }

    FaceitRateLimiter(EnrichmentProperties properties, Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.cap = Math.max(1, properties.getFaceit().getPerHour());
        this.spacing = Duration.ofNanos(1_000_000_000L / Math.max(1, properties.getFaceit().getPerSecond()));
    }

    /** The budget every FACEIT call is taken from; its {@code tryAcquire} may block up to {@link #MAX_WAIT}. */
    public FetchBudget budget() {
        return new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return acquire();
            }

            @Override
            public Duration retryAfter() {
                return retryAfterOf();
            }
        };
    }

    /** Refuses every call for {@code duration} (FACEIT answered 429). */
    public synchronized void pause(Duration duration) {
        Instant until = clock.instant().plus(duration);
        if (until.isAfter(pausedUntil)) {
            pausedUntil = until;
        }
    }

    public synchronized boolean paused() {
        return clock.instant().isBefore(pausedUntil);
    }

    private boolean acquire() {
        Duration wait = reserve();
        if (wait == null) {
            return false;
        }
        if (!wait.isZero()) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                // the slot stays reserved: it only makes the next call wait a little longer
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Reserves the next free slot; how long to wait for it, or null when the call is refused. */
    private synchronized Duration reserve() {
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return null;
        }
        evict(now);
        if (calls.size() >= cap) {
            return null;
        }
        Instant slot = now;
        if (!calls.isEmpty() && calls.peekLast().plus(spacing).isAfter(now)) {
            slot = calls.peekLast().plus(spacing);
        }
        Duration wait = Duration.between(now, slot);
        if (wait.compareTo(MAX_WAIT) > 0) {
            return null;
        }
        calls.addLast(slot);
        return wait;
    }

    private synchronized Duration retryAfterOf() {
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return Duration.between(now, pausedUntil);
        }
        evict(now);
        Instant free = now;
        if (calls.size() >= cap && !calls.isEmpty()) {
            free = calls.peekFirst().plus(WINDOW);
        }
        if (!calls.isEmpty() && calls.peekLast().plus(spacing).isAfter(free)) {
            free = calls.peekLast().plus(spacing);
        }
        Duration left = Duration.between(now, free);
        return left.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : left;
    }

    private void evict(Instant now) {
        Instant limit = now.minus(WINDOW);
        while (!calls.isEmpty() && !calls.peekFirst().isAfter(limit)) {
            calls.removeFirst();
        }
    }
}
