package fr.enimaloc.catapult.service.enrichment.speedrun;

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
 * Outbound limiter of the speedrun.com calls, in memory, over a sliding one-minute window. Member-triggered calls
 * may use the whole cap; public ones are held to half of it (and also count in the whole). A 420 answer from
 * speedrun.com {@linkplain #pause pauses} every call.
 */
@Component
public class SpeedrunRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final int cap;
    private final Deque<Instant> all = new ArrayDeque<>();
    private final Deque<Instant> publicCalls = new ArrayDeque<>();
    private Instant pausedUntil = Instant.MIN;

    @Autowired
    public SpeedrunRateLimiter(EnrichmentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    SpeedrunRateLimiter(EnrichmentProperties properties, Clock clock) {
        this.clock = clock;
        this.cap = properties.speedrunPerMinute();
    }

    /** Budget of the calls a member triggers: the whole cap. */
    public FetchBudget member() {
        return budget(false);
    }

    /** Budget of the calls the public routes trigger: half the cap, counted in the whole one. */
    public FetchBudget publicShare() {
        return budget(true);
    }

    /** Refuses every call for {@code duration} (speedrun.com answered 420). */
    public synchronized void pause(Duration duration) {
        Instant until = clock.instant().plus(duration);
        if (until.isAfter(pausedUntil)) {
            pausedUntil = until;
        }
    }

    public synchronized boolean paused() {
        return clock.instant().isBefore(pausedUntil);
    }

    private FetchBudget budget(boolean isPublic) {
        return new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return acquire(isPublic);
            }

            @Override
            public Duration retryAfter() {
                return retryAfterOf(isPublic);
            }
        };
    }

    private synchronized boolean acquire(boolean isPublic) {
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return false;
        }
        evict(now);
        if (all.size() >= cap || isPublic && publicCalls.size() >= cap / 2) {
            return false;
        }
        all.addLast(now);
        if (isPublic) {
            publicCalls.addLast(now);
        }
        return true;
    }

    private synchronized Duration retryAfterOf(boolean isPublic) {
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return Duration.between(now, pausedUntil);
        }
        evict(now);
        Instant free = now;
        if (all.size() >= cap && !all.isEmpty()) {
            free = all.peekFirst().plus(WINDOW);
        }
        if (isPublic && publicCalls.size() >= cap / 2 && !publicCalls.isEmpty()) {
            Instant share = publicCalls.peekFirst().plus(WINDOW);
            if (share.isAfter(free)) {
                free = share;
            }
        }
        Duration left = Duration.between(now, free);
        return left.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : left;
    }

    private void evict(Instant now) {
        Instant limit = now.minus(WINDOW);
        while (!all.isEmpty() && !all.peekFirst().isAfter(limit)) {
            all.removeFirst();
        }
        while (!publicCalls.isEmpty() && !publicCalls.peekFirst().isAfter(limit)) {
            publicCalls.removeFirst();
        }
    }
}
