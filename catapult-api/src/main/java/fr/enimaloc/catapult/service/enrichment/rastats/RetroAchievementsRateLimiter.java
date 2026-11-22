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
 * second. A 429 answer {@linkplain #pause pauses} every call.
 */
@Component
public class RetroAchievementsRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration SPACING = Duration.ofSeconds(1);

    private final Clock clock;
    private final int cap;
    private final Deque<Instant> calls = new ArrayDeque<>();
    private Instant pausedUntil = Instant.MIN;

    @Autowired
    public RetroAchievementsRateLimiter(EnrichmentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RetroAchievementsRateLimiter(EnrichmentProperties properties, Clock clock) {
        this.clock = clock;
        this.cap   = properties.raPerMinute();
    }

    /** The budget every RetroAchievements call is taken from. */
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

    private synchronized boolean acquire() {
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return false;
        }
        evict(now);
        if (calls.size() >= cap || !calls.isEmpty() && calls.peekLast().plus(SPACING).isAfter(now)) {
            return false;
        }
        calls.addLast(now);
        return true;
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
