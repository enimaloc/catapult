package fr.enimaloc.catapult.service.enrichment.riot;

import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbound limiter of the Riot calls, in memory, one bucket per key and host (Riot counts its limits per key and per
 * region). Each bucket has a spacing between two calls that waits (the slot is reserved under the bucket's lock and
 * the wait happens outside it; a slot more than {@link #MAX_WAIT} away is refused) and a sliding window that
 * refuses. Until Riot's {@code X-App-Rate-Limit} header is read, the limits are the configured ones (the personal
 * key's 20 / s and 100 / 2 min); a header read replaces them, with a margin. A 429 {@linkplain #pause pauses} the
 * bucket: Riot's method limits are not modelled apart, so a method 429 pauses every route of that key on that host
 * (a conservative choice). The window is counted per second (one counter per second that had calls), so a bucket
 * holds at most one entry per second of its window whatever the limit. Not shared with the presence detection.
 */
@Component
public class RiotRateLimiter {

    /** The longest wait for a slot; beyond it the call is refused. */
    static final Duration MAX_WAIT = Duration.ofSeconds(1);
    /** Share of a limit Riot announces that is used: a margin for the other instances and clock skew. */
    static final double MARGIN = 0.9;

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** One key on one host. Guarded by its own monitor. */
    private static final class Bucket {
        Duration spacing;
        int cap;
        Duration window;
        /** Per second of the window that had calls: {epoch second, calls}. */
        final Deque<long[]> seconds = new ArrayDeque<>();
        int inWindow;
        Instant last = Instant.MIN;
        Instant pausedUntil = Instant.MIN;

        Bucket(Duration spacing, int cap, Duration window) {
            this.spacing = spacing;
            this.cap = cap;
            this.window = window;
        }
    }

    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration defaultSpacing;
    private final int defaultCap;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public RiotRateLimiter(EnrichmentProperties properties) {
        this(properties, Clock.systemUTC(), Thread::sleep);
    }

    RiotRateLimiter(EnrichmentProperties properties, Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.defaultSpacing = Duration.ofNanos(1_000_000_000L / Math.max(1, properties.getRiot().getPerSecond()));
        this.defaultCap = Math.max(1, properties.getRiot().getPerTwoMinutes());
    }

    private Bucket bucket(String name) {
        return buckets.computeIfAbsent(name, n -> new Bucket(defaultSpacing, defaultCap, Duration.ofMinutes(2)));
    }

    /** Takes a slot of {@code bucket}, waiting up to {@link #MAX_WAIT}; false when refused. */
    public boolean acquire(String bucket) {
        Duration wait = reserve(bucket(bucket));
        if (wait == null) {
            return false;
        }
        if (!wait.isZero()) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private Duration reserve(Bucket b) {
        synchronized (b) {
            Instant now = clock.instant();
            if (now.isBefore(b.pausedUntil)) {
                return null;
            }
            long limit = now.minus(b.window).getEpochSecond();
            while (!b.seconds.isEmpty() && b.seconds.peekFirst()[0] <= limit) {
                b.inWindow -= (int) b.seconds.removeFirst()[1];
            }
            if (b.inWindow >= b.cap) {
                return null;
            }
            Instant slot = b.last.plus(b.spacing).isAfter(now) ? b.last.plus(b.spacing) : now;
            Duration wait = Duration.between(now, slot);
            if (wait.compareTo(MAX_WAIT) > 0) {
                return null;
            }
            b.last = slot;
            long second = slot.getEpochSecond();
            if (!b.seconds.isEmpty() && b.seconds.peekLast()[0] == second) {
                b.seconds.peekLast()[1]++;
            } else {
                b.seconds.addLast(new long[]{second, 1});
            }
            b.inWindow++;
            return wait;
        }
    }

    /** Refuses every call of {@code bucket} for {@code duration} (Riot answered 429). */
    public void pause(String bucket, Duration duration) {
        Bucket b = bucket(bucket);
        synchronized (b) {
            Instant until = clock.instant().plus(duration);
            if (until.isAfter(b.pausedUntil)) {
                b.pausedUntil = until;
            }
        }
    }

    public boolean paused(String bucket) {
        Bucket b = bucket(bucket);
        synchronized (b) {
            return clock.instant().isBefore(b.pausedUntil);
        }
    }

    /**
     * Adopts the limits of an {@code X-App-Rate-Limit} header ({@code 20:1,100:120}: calls per window in seconds),
     * with the {@link #MARGIN}: the shortest window sets the spacing, the longest the sliding window. A header that
     * cannot be read changes nothing.
     */
    public void learn(String bucket, String header) {
        if (header == null || header.isBlank()) {
            return;
        }
        int shortCalls = -1;
        long shortSeconds = Long.MAX_VALUE;
        int longCalls = -1;
        long longSeconds = -1;
        for (String part : header.split(",")) {
            String[] pair = part.trim().split(":");
            if (pair.length != 2) {
                return;
            }
            int calls;
            long seconds;
            try {
                calls = Integer.parseInt(pair[0].trim());
                seconds = Long.parseLong(pair[1].trim());
            } catch (NumberFormatException e) {
                return;
            }
            if (calls <= 0 || seconds <= 0 || seconds > 86_400) {
                return;
            }
            if (seconds < shortSeconds) {
                shortSeconds = seconds;
                shortCalls = calls;
            }
            if (seconds > longSeconds) {
                longSeconds = seconds;
                longCalls = calls;
            }
        }
        if (shortCalls < 0) {
            return;
        }
        int spaced = Math.max(1, (int) Math.floor(shortCalls * MARGIN));
        Duration spacing = Duration.ofNanos(shortSeconds * 1_000_000_000L / spaced);
        int cap = Math.max(1, (int) Math.floor(longCalls * MARGIN));
        Duration window = Duration.ofSeconds(longSeconds);
        Bucket b = bucket(bucket);
        synchronized (b) {
            b.spacing = spacing;
            b.cap = cap;
            b.window = window;
        }
    }

    /** How many seconds a bucket keeps a counter for, for the tests. */
    int remembered(String bucket) {
        Bucket b = bucket(bucket);
        synchronized (b) {
            return b.seconds.size();
        }
    }

    /** The current spacing and cap of a bucket, for the tests. */
    String describe(String bucket) {
        Bucket b = bucket(bucket);
        synchronized (b) {
            return b.spacing.toMillis() + "ms/" + b.cap + "per" + b.window.toSeconds() + "s";
        }
    }
}
