package fr.enimaloc.catapult.service.enrichment.rastats;

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
 * Outbound limiter of the RetroAchievements calls, in memory: a sliding one-minute window, and at most one call per
 * second. A call that comes less than a second after the previous one waits for its slot (at most
 * {@link #MAX_WAIT}; a later slot is refused), so that a read made of two back-to-back calls goes through; the slot is
 * reserved under the lock and the wait happens outside it. The minute cap is never waited for: it refuses. A 429
 * answer {@linkplain #pause pauses} every call.
 */
@Component
public class RetroAchievementsRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration SPACING = Duration.ofSeconds(1);
    /** The longest wait for a slot; beyond it the call is refused. */
    static final Duration MAX_WAIT = Duration.ofMillis(1500);

    /** Waits; {@link Thread#sleep(Duration)} outside of tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Clock clock;
    private final Sleeper sleeper;
    private final int cap;
    private final Deque<Instant> calls = new ArrayDeque<>();
    private Instant pausedUntil = Instant.MIN;

    @Autowired
    public RetroAchievementsRateLimiter(EnrichmentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RetroAchievementsRateLimiter(EnrichmentProperties properties, Clock clock) {
        this(properties, clock, Thread::sleep);
    }

    RetroAchievementsRateLimiter(EnrichmentProperties properties, Clock clock, Sleeper sleeper) {
        this.clock   = clock;
        this.sleeper = sleeper;
        this.cap     = properties.raPerMinute();
    }

    /**
     * The budget every RetroAchievements call is taken from. Its {@code tryAcquire} may block up to
     * {@link #MAX_WAIT} for the one-second spacing.
     */
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

    /** Refuses every call for {@code duration} (RetroAchievements answered 429). */
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
        if (!calls.isEmpty() && calls.peekLast().plus(SPACING).isAfter(now)) {
            slot = calls.peekLast().plus(SPACING);
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
        if (!calls.isEmpty() && calls.peekLast().plus(SPACING).isAfter(free)) {
            free = calls.peekLast().plus(SPACING);
        }
        Duration left = Duration.between(now, free);
        return left.compareTo(SPACING) < 0 ? SPACING : left;
    }

    private void evict(Instant now) {
        Instant limit = now.minus(WINDOW);
        while (!calls.isEmpty() && !calls.peekFirst().isAfter(limit)) {
            calls.removeFirst();
        }
    }
}
