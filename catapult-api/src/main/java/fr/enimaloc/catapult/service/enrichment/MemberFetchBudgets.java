package fr.enimaloc.catapult.service.enrichment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps the upstream calls one member may trigger through the personal stats routes: a sliding one-minute window per
 * member, in memory, so that a token walking a library cannot drain the shared platform quotas. The limit is
 * {@link EnrichmentProperties#memberPerMinute()}.
 */
@Component
public class MemberFetchBudgets {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final EnrichmentProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Deque<Instant>> calls = new ConcurrentHashMap<>();

    @Autowired
    public MemberFetchBudgets(EnrichmentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    MemberFetchBudgets(EnrichmentProperties properties, Clock clock) {
        this.properties = properties;
        this.clock      = clock;
    }

    /** The budget of {@code userId}; every {@link FetchBudget#tryAcquire()} counts against that member. */
    public FetchBudget forMember(UUID userId) {
        return new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return acquire(userId);
            }

            @Override
            public Duration retryAfter() {
                return untilOldestLeaves(userId);
            }
        };
    }

    private boolean acquire(UUID userId) {
        boolean[] granted = new boolean[1];
        calls.compute(userId, (key, window) -> {
            Instant now = clock.instant();
            Deque<Instant> deque = window == null ? new ArrayDeque<>() : window;
            drop(deque, now);
            if (deque.size() < properties.memberPerMinute()) {
                deque.addLast(now);
                granted[0] = true;
            }
            return deque;
        });
        return granted[0];
    }

    private Duration untilOldestLeaves(UUID userId) {
        Duration[] wait = {WINDOW};
        calls.computeIfPresent(userId, (key, window) -> {
            Instant now = clock.instant();
            drop(window, now);
            if (!window.isEmpty()) {
                wait[0] = Duration.between(now, window.peekFirst().plus(WINDOW));
            }
            return window;
        });
        return wait[0];
    }

    /** Forgets the members that have made no call for a full window, so the map cannot grow without bound. */
    @Scheduled(fixedDelayString = "PT1M")
    public void evictIdle() {
        Instant now = clock.instant();
        calls.keySet().forEach(id -> calls.computeIfPresent(id, (key, window) -> {
            drop(window, now);
            return window.isEmpty() ? null : window;
        }));
    }

    /** Number of members currently tracked. */
    int trackedMembers() {
        return calls.size();
    }

    private static void drop(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
            window.removeFirst();
        }
    }
}
