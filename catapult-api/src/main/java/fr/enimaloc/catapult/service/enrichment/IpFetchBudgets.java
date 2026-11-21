package fr.enimaloc.catapult.service.enrichment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps the upstream calls one client IP may trigger: a sliding one-minute window per IP, in memory.
 * The limit is {@link EnrichmentProperties#ipBudgetPerMinute()}; the IP is the request's remote address
 * (already the real client IP thanks to {@code server.forward-headers-strategy=native}).
 */
@Component
public class IpFetchBudgets {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final EnrichmentProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> calls = new ConcurrentHashMap<>();

    @Autowired
    public IpFetchBudgets(EnrichmentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    IpFetchBudgets(EnrichmentProperties properties, Clock clock) {
        this.properties = properties;
        this.clock      = clock;
    }

    /** The budget of {@code ip}; every call to {@link FetchBudget#tryAcquire()} counts against that IP. */
    public FetchBudget forIp(String ip) {
        return new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return acquire(ip);
            }

            @Override
            public Duration retryAfter() {
                return untilOldestLeaves(ip);
            }
        };
    }

    private boolean acquire(String ip) {
        boolean[] granted = new boolean[1];
        calls.compute(ip, (key, window) -> {
            Instant now = clock.instant();
            Deque<Instant> deque = window == null ? new ArrayDeque<>() : window;
            drop(deque, now);
            if (deque.size() < properties.ipBudgetPerMinute()) {
                deque.addLast(now);
                granted[0] = true;
            }
            return deque;
        });
        return granted[0];
    }

    private Duration untilOldestLeaves(String ip) {
        Duration[] wait = {WINDOW};
        calls.computeIfPresent(ip, (key, window) -> {
            Instant now = clock.instant();
            drop(window, now);
            if (!window.isEmpty()) {
                wait[0] = Duration.between(now, window.peekFirst().plus(WINDOW));
            }
            return window;
        });
        return wait[0];
    }

    /** Forgets the IPs that have made no call for a full window, so the map cannot grow without bound. */
    @Scheduled(fixedDelayString = "PT1M")
    public void evictIdle() {
        Instant now = clock.instant();
        calls.keySet().forEach(ip -> calls.computeIfPresent(ip, (key, window) -> {
            drop(window, now);
            return window.isEmpty() ? null : window;
        }));
    }

    /** Number of IPs currently tracked. */
    int trackedIps() {
        return calls.size();
    }

    private static void drop(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
            window.removeFirst();
        }
    }
}
