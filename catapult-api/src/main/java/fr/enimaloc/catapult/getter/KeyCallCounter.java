package fr.enimaloc.catapult.getter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The calls sent with each shared API key, for the admin Keys tab: those in the last {@code window} and those in the
 * current period (a day, a month: what {@code periodOf} names an instant's). Counted on this instance only.
 */
public final class KeyCallCounter {

    private final Duration window;
    private final Function<Instant, Object> periodOf;
    private final Map<String, Counts> byKey = new ConcurrentHashMap<>();

    private static final class Counts {
        final Deque<Instant> recent = new ArrayDeque<>();
        Object period;
        long inPeriod;
    }

    public KeyCallCounter(Duration window, Function<Instant, Object> periodOf) {
        this.window = window;
        this.periodOf = periodOf;
    }

    public void record(String key, Instant now) {
        Counts c = byKey.computeIfAbsent(key, k -> new Counts());
        synchronized (c) {
            c.recent.addLast(now);
            prune(c, now);
            Object period = periodOf.apply(now);
            if (!period.equals(c.period)) {
                c.period = period;
                c.inPeriod = 0;
            }
            c.inPeriod++;
        }
    }

    /** The calls sent with {@code key} in the last window. */
    public int inWindow(String key, Instant now) {
        Counts c = byKey.get(key);
        if (c == null) return 0;
        synchronized (c) {
            prune(c, now);
            return c.recent.size();
        }
    }

    /** The calls sent with {@code key} in the current period. */
    public long inPeriod(String key, Instant now) {
        Counts c = byKey.get(key);
        if (c == null) return 0;
        synchronized (c) {
            return periodOf.apply(now).equals(c.period) ? c.inPeriod : 0;
        }
    }

    private void prune(Counts c, Instant now) {
        Instant from = now.minus(window);
        while (!c.recent.isEmpty() && !c.recent.peekFirst().isAfter(from)) c.recent.removeFirst();
    }
}
