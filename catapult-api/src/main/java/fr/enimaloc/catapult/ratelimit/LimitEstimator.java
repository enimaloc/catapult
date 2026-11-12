package fr.enimaloc.catapult.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * A scope's limit, guessed from its 429s: the calls sent in the window the platform seems to count, minus the one it
 * refused. Only tightened by a 429; raised by a tenth after a long time near it without any. Not thread-safe: its
 * {@link RateLimitScope} guards it.
 */
final class LimitEstimator {

    /** The windows a platform may count calls in, shortest first. */
    static final List<Duration> WINDOWS = List.of(Duration.ofSeconds(10), Duration.ofMinutes(1),
            Duration.ofMinutes(5), Duration.ofHours(1));
    private static final Duration KEPT = WINDOWS.getLast();
    private static final int MAX_KEPT = 10_000;

    /** A limit: at most {@code calls} in any {@code window}. */
    record Estimate(int calls, Duration window) {}

    private final Duration raiseAfter;
    private final Deque<Instant> calls = new ArrayDeque<>();
    private Estimate estimate;
    private Instant last429;
    private Instant lastRaise;
    /** Since when every call is counted: a 429 tells a limit only for a window counted whole. */
    private Instant countingSince;

    LimitEstimator(Duration raiseAfter, Instant countingSince) {
        this.raiseAfter = raiseAfter;
        this.countingSince = countingSince;
    }

    void record(Instant at) {
        calls.addLast(at);
        prune(at);
    }

    int countWithin(Duration window, Instant now) {
        Instant from = now.minus(window);
        int n = 0;
        for (Iterator<Instant> it = calls.descendingIterator(); it.hasNext(); ) {
            if (!it.next().isAfter(from)) break;
            n++;
        }
        return n;
    }

    /** The oldest call still counted in {@code window}: the limit frees a call when it leaves the window. */
    Optional<Instant> oldestWithin(Duration window, Instant now) {
        Instant from = now.minus(window);
        return calls.stream().filter(at -> at.isAfter(from)).findFirst();
    }

    Optional<Estimate> estimate() {
        return Optional.ofNullable(estimate);
    }

    /**
     * A 429: the window is the shortest one at least as long as the wait the platform asks or the one learned
     * ({@code resumeHint}), the limit the calls sent in it before the refused one. Never loosened by a 429, and at
     * most halved by one in the same window: a 429 after few calls tells more of a longer window than of a tiny limit.
     * Nothing is learned when the window wasn't counted whole (just started, or a wait ended within it): the calls
     * the platform counted aren't all known.
     */
    void on429(Instant now, Duration resumeHint) {
        last429 = now;
        Duration window = WINDOWS.stream().filter(w -> w.compareTo(resumeHint) >= 0).findFirst().orElse(KEPT);
        if (countingSince.isAfter(now.minus(window))) return;
        int limit = Math.max(1, countWithin(window, now) - 1);
        if (estimate != null && estimate.window().equals(window)) {
            int halved = (estimate.calls() + 1) / 2;
            estimate = new Estimate(Math.min(estimate.calls(), Math.max(limit, halved)), window);
        } else {
            estimate = new Estimate(limit, window);
        }
    }

    /** Raises the limit by a tenth (at least one call) when it was nearly reached for long without a 429. */
    void maybeRaise(Instant now) {
        if (estimate == null) return;
        Instant since = last429 == null ? lastRaise
                : lastRaise == null || last429.isAfter(lastRaise) ? last429 : lastRaise;
        if (since != null && since.plus(raiseAfter).isAfter(now)) return;
        if (countWithin(estimate.window(), now) * 10 < estimate.calls() * 8) return;
        estimate = new Estimate(estimate.calls() + Math.max(1, estimate.calls() / 10), estimate.window());
        lastRaise = now;
    }

    /** Forgets the calls counted: after a 429's wait (ending {@code from}), the platform counts afresh. */
    void forgetCalls(Instant from) {
        calls.clear();
        countingSince = from;
    }

    void set(Estimate estimate, Instant last429) {
        this.estimate = estimate;
        this.last429 = last429;
    }

    void reset() {
        estimate = null;
        last429 = null;
        lastRaise = null;
    }

    private void prune(Instant now) {
        Instant from = now.minus(KEPT);
        while (!calls.isEmpty() && (calls.size() > MAX_KEPT || !calls.peekFirst().isAfter(from))) calls.removeFirst();
    }
}
