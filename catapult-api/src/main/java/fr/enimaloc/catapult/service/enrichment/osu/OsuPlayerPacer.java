package fr.enimaloc.catapult.service.enrichment.osu;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * osu!'s rule that a player be asked at most once a minute, per read ({@code key}, which names the member as
 * {@code u:<uuid>:}): a read less than {@link #PER_PLAYER} after the previous one of the same key is not sent. A
 * caller crossing a read still under way waits for it (at most {@link #WAIT}) and shares its answer instead of
 * failing; one coming after a finished read within the minute gets that read's answer when it succeeded, else an
 * outage. Unlinking or deleting the member forgets their reads ({@link #forget}); finished reads are forgotten once
 * their minute is over.
 */
@Component
public class OsuPlayerPacer {

    /** The least time between two reads of one member's same data (osu!'s rule). */
    static final Duration PER_PLAYER = Duration.ofMinutes(1);
    /** The longest a caller waits for a read under way: the limiter's wait plus a read timeout. */
    static final Duration WAIT = Duration.ofSeconds(15);

    private record Slot(Instant at, CompletableFuture<Object> answer) {
    }

    private final Clock clock;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    @Autowired
    public OsuPlayerPacer() {
        this(Clock.systemUTC());
    }

    OsuPlayerPacer(Clock clock) {
        this.clock = clock;
    }

    /**
     * {@code fetch} when the key's previous read is over a minute old (or there is none); otherwise the answer of
     * that read.
     *
     * @throws OsuUnavailableException when the previous read within the minute failed, or the one under way does not
     *         answer in time
     */
    @SuppressWarnings("unchecked")
    public <T> T pace(String key, Supplier<T> fetch) {
        Instant now = clock.instant();
        slots.values().removeIf(slot -> slot.answer().isDone() && !slot.at().plus(PER_PLAYER).isAfter(now));
        CompletableFuture<Object> mine = new CompletableFuture<>();
        Slot[] previous = new Slot[1];
        slots.compute(key, (k, slot) -> {
            if (slot != null && (!slot.answer().isDone() || slot.at().plus(PER_PLAYER).isAfter(now))) {
                previous[0] = slot;
                return slot;
            }
            return new Slot(now, mine);
        });
        if (previous[0] == null) {
            try {
                T value = fetch.get();
                mine.complete(value);
                return value;
            } catch (RuntimeException e) {
                mine.completeExceptionally(e);
                throw e;
            }
        }
        try {
            return (T) previous[0].answer().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new OsuUnavailableException("this osu! player was asked less than a minute ago");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OsuUnavailableException("interrupted while waiting for the osu! read under way");
        }
    }

    /** Forgets the member's reads: the next one is sent at once (unlinked, linked again, account deleted). */
    public void forget(UUID userId) {
        String member = "u:" + userId + ":";
        slots.keySet().removeIf(key -> key.contains(member));
    }

    int size() {
        return slots.size();
    }
}
