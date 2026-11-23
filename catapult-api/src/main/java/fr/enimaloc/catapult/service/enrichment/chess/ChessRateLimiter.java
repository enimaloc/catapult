package fr.enimaloc.catapult.service.enrichment.chess;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Outbound limiter of the Chess.com and Lichess calls, in memory: one request in flight per site (both ask for
 * serial requests; Lichess, one at a time), first come first served. A call waits for the site's slot at most
 * {@link #MAX_WAIT}, without holding any lock while it waits, then is refused. A 429 {@linkplain #pause pauses} the
 * site. Not shared with anything else: the game detection never asks these sites.
 */
@Component
public class ChessRateLimiter {

    /** The longest wait for a site's slot; beyond it the call is refused. */
    static final Duration MAX_WAIT = Duration.ofSeconds(2);

    private final Duration maxWait;
    private final Clock clock;
    private final Map<ChessSite, Semaphore> slots = new EnumMap<>(ChessSite.class);
    private final Map<ChessSite, Instant> pausedUntil = new EnumMap<>(ChessSite.class);

    @Autowired
    public ChessRateLimiter() {
        this(MAX_WAIT, Clock.systemUTC());
    }

    ChessRateLimiter(Duration maxWait, Clock clock) {
        this.maxWait = maxWait;
        this.clock = clock;
        for (ChessSite site : ChessSite.values()) {
            slots.put(site, new Semaphore(1, true));
            pausedUntil.put(site, Instant.MIN);
        }
    }

    /**
     * Runs {@code request} once the site's slot is free, and frees it after (whatever the outcome).
     *
     * @throws ChessUnavailableException when the site is paused or its slot is not free within the bound
     */
    public <T> T call(ChessSite site, Supplier<T> request) {
        if (paused(site)) {
            throw refused(site);
        }
        Semaphore slot = slots.get(site);
        boolean acquired;
        try {
            acquired = slot.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw refused(site);
        }
        if (!acquired) {
            throw refused(site);
        }
        try {
            if (paused(site)) {
                throw refused(site);
            }
            return request.get();
        } finally {
            slot.release();
        }
    }

    /** Refuses every call to {@code site} for {@code duration} (it answered 429); a longer pause is kept. */
    public synchronized void pause(ChessSite site, Duration duration) {
        Instant until = clock.instant().plus(duration);
        if (until.isAfter(pausedUntil.get(site))) {
            pausedUntil.put(site, until);
        }
    }

    public synchronized boolean paused(ChessSite site) {
        return clock.instant().isBefore(pausedUntil.get(site));
    }

    private static ChessUnavailableException refused(ChessSite site) {
        return new ChessUnavailableException("the " + site + " rate limit does not allow a request now");
    }
}
