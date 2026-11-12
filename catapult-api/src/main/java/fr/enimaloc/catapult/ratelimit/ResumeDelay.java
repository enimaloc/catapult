package fr.enimaloc.catapult.ratelimit;

import java.time.Duration;
import java.time.Instant;

/**
 * How long a scope waits after a 429 without a Retry-After, learned from the first call after each wait: an answer
 * tries a fifth shorter next time, another 429 doubles it. Never shorter than the longest wait that failed lately
 * ({@code fail}, forgotten after a long time without failing). Not thread-safe: its {@link RateLimitScope} guards it.
 */
final class ResumeDelay {

    static final Duration INITIAL = Duration.ofSeconds(60);
    static final Duration MAX = Duration.ofMinutes(10);
    private static final Duration MIN = Duration.ofSeconds(1);

    /** What is kept of it. */
    record State(Duration current, Duration fail, Duration ok, Instant lastFail) {}

    private final Duration forgetFailAfter;
    private Duration current = INITIAL;
    private Duration fail;
    private Duration ok;
    private Instant lastFail;

    ResumeDelay(Duration forgetFailAfter) {
        this.forgetFailAfter = forgetFailAfter;
    }

    Duration current() {
        return current;
    }

    /** The first call after waiting {@link #current} got a 429 again: too short. */
    void onFailure(Instant now) {
        forgetOldFailure(now);
        fail = fail == null || current.compareTo(fail) > 0 ? current : fail;
        Duration doubled = current.multipliedBy(2);
        current = doubled.compareTo(MAX) > 0 ? MAX : doubled;
        lastFail = now;
    }

    /** The first call after waiting {@link #current} was answered: long enough, a shorter wait is tried next. */
    void onSuccess(Instant now) {
        forgetOldFailure(now);
        ok = ok == null || current.compareTo(ok) < 0 ? current : ok;
        Duration shorter = current.multipliedBy(4).dividedBy(5);
        if (fail != null && shorter.compareTo(fail) < 0) shorter = fail;
        current = shorter.compareTo(MIN) < 0 ? MIN : shorter;
    }

    State state() {
        return new State(current, fail, ok, lastFail);
    }

    void restore(State state) {
        current = state.current() == null ? INITIAL : state.current();
        fail = state.fail();
        ok = state.ok();
        lastFail = state.lastFail();
    }

    void reset() {
        restore(new State(INITIAL, null, null, null));
    }

    private void forgetOldFailure(Instant now) {
        if (lastFail != null && lastFail.plus(forgetFailAfter).isBefore(now)) {
            fail = null;
            lastFail = null;
        }
    }
}
