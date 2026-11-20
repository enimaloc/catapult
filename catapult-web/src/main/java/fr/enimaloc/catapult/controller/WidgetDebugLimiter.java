package fr.enimaloc.catapult.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bounds what OBS widgets may post of their debug exchanges ({@code /widget/obs/{token}/debug/client-exchanges},
 * open to anyone holding a token), on this instance. Only a token catapult-api has recognized (a relay of it answered
 * neither 404 nor a 5xx) gets an allowance of its own, {@link #BATCHES} batches per {@link #WINDOW}, and stays
 * recognized for {@link #KNOWN_FOR}. Every other token draws on one budget shared by all of them,
 * {@link #UNVERIFIED_BATCHES} per {@link #WINDOW}: random tokens can neither flood catapult-api nor take a known
 * widget's place. The known widgets are bounded ({@link #KNOWN}), the least recently seen leaving first.
 */
@Component
public class WidgetDebugLimiter {
    static final int BATCHES = 10;
    static final int UNVERIFIED_BATCHES = 20;
    static final Duration WINDOW = Duration.ofSeconds(10);
    static final Duration KNOWN_FOR = Duration.ofMinutes(10);
    static final int KNOWN = 10_000;

    /** What a widget's batch may do: be relayed on its own allowance or the shared one, or be refused (429). */
    public enum Admission { KNOWN, UNVERIFIED, REFUSED }

    /** A recognized widget: until when, and the batches it sent within the window. */
    private static final class Known {
        Instant until;
        final Deque<Instant> sent = new ArrayDeque<>();
    }

    private final Clock clock;
    private final Map<UUID, Known> known;
    private final Deque<Instant> unverified = new ArrayDeque<>();

    @Autowired
    public WidgetDebugLimiter() {
        this(Clock.systemUTC(), KNOWN);
    }

    WidgetDebugLimiter(Clock clock, int knownCap) {
        this.clock = clock;
        // Access order, capped: the least recently seen known widget leaves first, without any scan
        this.known = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Known> eldest) {
                return size() > knownCap;
            }
        };
    }

    /** Whether a batch of {@code widget} may be relayed now, counted against the allowance it is admitted on. */
    public synchronized Admission admit(UUID widget) {
        Instant now = clock.instant();
        Instant since = now.minus(WINDOW);
        Known entry = known.get(widget);
        if (entry != null && !now.isBefore(entry.until)) {
            known.remove(widget);
            entry = null;
        }
        if (entry != null) {
            return take(entry.sent, since, now, BATCHES) ? Admission.KNOWN : Admission.REFUSED;
        }
        return take(unverified, since, now, UNVERIFIED_BATCHES) ? Admission.UNVERIFIED : Admission.REFUSED;
    }

    /**
     * What catapult-api answered to a relay of {@code widget}'s batch: a 404 forgets the widget, a 5xx tells nothing,
     * anything else makes it known for {@link #KNOWN_FOR} (that relay counted in its own allowance).
     */
    public synchronized void relayed(UUID widget, int status) {
        if (status == 404) {
            known.remove(widget);
            return;
        }
        if (status >= 500) {
            return;
        }
        Instant now = clock.instant();
        Known entry = known.get(widget);
        if (entry == null) {
            entry = new Known();
            entry.sent.addLast(now);
            known.put(widget, entry);
        }
        entry.until = now.plus(KNOWN_FOR);
    }

    /** Whether {@code times} has room for one more since {@code since}, which is then counted at {@code now}. */
    private static boolean take(Deque<Instant> times, Instant since, Instant now, int max) {
        while (!times.isEmpty() && !times.peekFirst().isAfter(since)) {
            times.pollFirst();
        }
        if (times.size() >= max) {
            return false;
        }
        times.addLast(now);
        return true;
    }
}
