package fr.enimaloc.catapult.service.enrichment.xboxstats;

import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound limiter of the Xbox stats calls, in memory, over a sliding one-minute window: a cap per member, which also
 * counts in a cap on the whole. A 429 answer {@linkplain #pause pauses} that member's calls only (the Xbox limits are
 * per user token).
 */
@Component
public class XboxStatsRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final int total;
    private final int perMember;
    private final Deque<Instant> all = new ArrayDeque<>();
    private final Map<UUID, Deque<Instant>> byMember = new HashMap<>();
    private final Map<UUID, Instant> pausedUntil = new HashMap<>();

    @Autowired
    public XboxStatsRateLimiter(EnrichmentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    XboxStatsRateLimiter(EnrichmentProperties properties, Clock clock) {
        this.clock     = clock;
        this.total     = properties.xboxStatsPerMinute();
        this.perMember = properties.xboxStatsPerMemberPerMinute();
    }

    /** Budget of the calls made with {@code userId}'s token; each one also counts in the total. */
    public FetchBudget forMember(UUID userId) {
        return new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return acquire(userId);
            }

            @Override
            public Duration retryAfter() {
                return retryAfterOf(userId);
            }
        };
    }

    /** Refuses every call of {@code userId} for {@code duration} (Xbox answered 429). */
    public synchronized void pause(UUID userId, Duration duration) {
        Instant until = clock.instant().plus(duration);
        pausedUntil.merge(userId, until, (a, b) -> a.isAfter(b) ? a : b);
    }

    private synchronized boolean acquire(UUID userId) {
        Instant now = clock.instant();
        Instant paused = pausedUntil.get(userId);
        if (paused != null && now.isBefore(paused)) {
            return false;
        }
        evict(now);
        Deque<Instant> mine = byMember.computeIfAbsent(userId, id -> new ArrayDeque<>());
        if (all.size() >= total || mine.size() >= perMember) {
            return false;
        }
        all.addLast(now);
        mine.addLast(now);
        return true;
    }

    private synchronized Duration retryAfterOf(UUID userId) {
        Instant now = clock.instant();
        Instant paused = pausedUntil.get(userId);
        if (paused != null && now.isBefore(paused)) {
            return Duration.between(now, paused);
        }
        evict(now);
        Instant free = now;
        if (all.size() >= total && !all.isEmpty()) {
            free = all.peekFirst().plus(WINDOW);
        }
        Deque<Instant> mine = byMember.get(userId);
        if (mine != null && mine.size() >= perMember && !mine.isEmpty()) {
            Instant own = mine.peekFirst().plus(WINDOW);
            if (own.isAfter(free)) {
                free = own;
            }
        }
        Duration left = Duration.between(now, free);
        return left.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : left;
    }

    /** Forgets the members with no recent call and no pause, so the maps cannot grow without bound. */
    @Scheduled(fixedDelayString = "PT1M")
    public synchronized void evictIdle() {
        evict(clock.instant());
    }

    private void evict(Instant now) {
        Instant limit = now.minus(WINDOW);
        while (!all.isEmpty() && !all.peekFirst().isAfter(limit)) {
            all.removeFirst();
        }
        byMember.values().forEach(window -> {
            while (!window.isEmpty() && !window.peekFirst().isAfter(limit)) {
                window.removeFirst();
            }
        });
        byMember.values().removeIf(Deque::isEmpty);
        pausedUntil.values().removeIf(until -> !now.isBefore(until));
    }
}
