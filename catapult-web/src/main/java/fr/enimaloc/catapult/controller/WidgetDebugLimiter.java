package fr.enimaloc.catapult.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounds what an OBS widget may post of its debug exchanges ({@code /widget/obs/{token}/debug/client-exchanges},
 * open to anyone holding a token): {@link #BATCHES} batches per {@link #WINDOW} and widget, on this instance. The
 * widgets tracked are bounded too, so random tokens cannot fill the memory: past the bound, a widget not yet tracked
 * is refused until one of them goes quiet for a window.
 */
@Component
public class WidgetDebugLimiter {
    static final int BATCHES = 10;
    static final Duration WINDOW = Duration.ofSeconds(10);
    static final int TRACKED = 10_000;

    private final Clock clock;
    private final int tracked;
    private final Map<UUID, Deque<Instant>> recent = new ConcurrentHashMap<>();

    @Autowired
    public WidgetDebugLimiter() {
        this(Clock.systemUTC(), TRACKED);
    }

    WidgetDebugLimiter(Clock clock, int tracked) {
        this.clock = clock;
        this.tracked = tracked;
    }

    /** Whether the widget {@code widget} may post one more batch now, counted when it may. */
    public boolean tryAcquire(UUID widget) {
        Instant now = clock.instant();
        Instant since = now.minus(WINDOW);
        if (!recent.containsKey(widget) && recent.size() >= tracked) {
            // Each deque is only touched within the map's per-key atomic operations
            for (UUID quiet : recent.keySet()) {
                recent.computeIfPresent(quiet, (key, times) -> prune(times, since).isEmpty() ? null : times);
            }
            if (recent.size() >= tracked) {
                return false;
            }
        }
        boolean[] allowed = {false};
        recent.compute(widget, (key, times) -> {
            Deque<Instant> kept = times == null ? new ArrayDeque<>() : prune(times, since);
            if (kept.size() < BATCHES) {
                kept.addLast(now);
                allowed[0] = true;
            }
            return kept;
        });
        return allowed[0];
    }

    /** {@code times} without those up to {@code since}, which have left the window. */
    private static Deque<Instant> prune(Deque<Instant> times, Instant since) {
        while (!times.isEmpty() && !times.peekFirst().isAfter(since)) {
            times.pollFirst();
        }
        return times;
    }
}
