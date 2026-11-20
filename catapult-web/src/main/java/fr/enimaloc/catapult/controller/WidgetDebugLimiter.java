package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetAccessResponse;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
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
import java.util.function.Function;

/**
 * Gates what OBS widgets post of their debug exchanges ({@code /widget/obs/{token}/debug/client-exchanges}, open to
 * anyone holding a token), on this instance. A token is first checked with catapult-api (the widget's access, as its
 * stream does): an unknown or disabled widget is refused (404) with nothing relayed and nothing counted, so random
 * tokens take no valid widget's place. The answer is remembered, {@link #VALID_FOR} for a valid widget and
 * {@link #INVALID_FOR} for any other, each in a bounded map ({@link #CACHED}, the least recently seen leaving first);
 * catapult-api being unreachable is not remembered. A valid widget gets {@link #BATCHES} batches per {@link #WINDOW}.
 * The check runs outside the lock, so a slow catapult-api holds no other widget up.
 */
@Component
public class WidgetDebugLimiter {
    static final int BATCHES = 10;
    static final Duration WINDOW = Duration.ofSeconds(10);
    static final Duration VALID_FOR = Duration.ofMinutes(10);
    static final Duration INVALID_FOR = Duration.ofMinutes(1);
    static final int CACHED = 10_000;

    /** What catapult-api tells of a widget token. */
    enum Validity { VALID, INVALID, UNAVAILABLE }

    /** What a widget's batch may do: be relayed, or be refused as unknown (404), over its allowance (429), unchecked (502). */
    public enum Admission { ADMITTED, UNKNOWN, REFUSED, UNAVAILABLE }

    /** A valid widget: until when it is believed so, and the batches it sent within the window. */
    private static final class Valid {
        final Instant until;
        final Deque<Instant> sent = new ArrayDeque<>();

        Valid(Instant until) {
            this.until = until;
        }
    }

    private final Clock clock;
    private final Function<UUID, Validity> check;
    private final Map<UUID, Valid> valid;
    private final Map<UUID, Instant> invalid;

    @Autowired
    public WidgetDebugLimiter(ApiService apiService) {
        this(Clock.systemUTC(), CACHED, token -> validity(apiService.widgetAccess(token)));
    }

    WidgetDebugLimiter(Clock clock, int cap, Function<UUID, Validity> check) {
        this.clock = clock;
        this.check = check;
        this.valid = bounded(cap);
        this.invalid = bounded(cap);
    }

    /** An access-ordered map dropping its least recently seen entry past {@code cap}, without any scan. */
    private static <V> Map<UUID, V> bounded(int cap) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, V> eldest) {
                return size() > cap;
            }
        };
    }

    /** What catapult-api's answer on a widget's access tells. */
    static Validity validity(ApiClient.Result<TwitchatWidgetAccessResponse> access) {
        if (access == null) return Validity.UNAVAILABLE;
        if (access.status() == 200) {
            return access.body() != null && access.body().enabled() ? Validity.VALID : Validity.INVALID;
        }
        return access.status() == 404 ? Validity.INVALID : Validity.UNAVAILABLE;
    }

    /** Whether a batch of {@code widget} may be relayed now, counted in its allowance when it may. */
    public Admission admit(UUID widget) {
        Admission known = admitKnown(widget);
        if (known != null) return known;
        // Unknown here: asked outside the lock
        Validity validity = check.apply(widget);
        synchronized (this) {
            Instant now = clock.instant();
            switch (validity) {
                case INVALID -> {
                    invalid.put(widget, now.plus(INVALID_FOR));
                    return Admission.UNKNOWN;
                }
                case UNAVAILABLE -> {
                    return Admission.UNAVAILABLE;
                }
                case VALID -> {
                    Valid entry = valid.get(widget);
                    if (entry == null || !now.isBefore(entry.until)) {
                        entry = new Valid(now.plus(VALID_FOR));
                        valid.put(widget, entry);
                    }
                    return take(entry.sent, now);
                }
            }
        }
        throw new IllegalStateException("Unknown validity " + validity);
    }

    /** The admission of a widget whose validity is remembered; null when it is not (or no longer). */
    private synchronized Admission admitKnown(UUID widget) {
        Instant now = clock.instant();
        Valid entry = valid.get(widget);
        if (entry != null) {
            if (now.isBefore(entry.until)) return take(entry.sent, now);
            valid.remove(widget);
        }
        Instant invalidUntil = invalid.get(widget);
        if (invalidUntil != null) {
            if (now.isBefore(invalidUntil)) return Admission.UNKNOWN;
            invalid.remove(widget);
        }
        return null;
    }

    /** catapult-api answered {@code status} to a relay of {@code widget}'s batch: a 404 forgets it as valid. */
    public synchronized void relayed(UUID widget, int status) {
        if (status == 404) {
            valid.remove(widget);
            invalid.put(widget, clock.instant().plus(INVALID_FOR));
        }
    }

    /** One more batch in {@code sent} at {@code now} when the window has room for it. */
    private static Admission take(Deque<Instant> sent, Instant now) {
        Instant since = now.minus(WINDOW);
        while (!sent.isEmpty() && !sent.peekFirst().isAfter(since)) {
            sent.pollFirst();
        }
        if (sent.size() >= BATCHES) {
            return Admission.REFUSED;
        }
        sent.addLast(now);
        return Admission.ADMITTED;
    }
}
