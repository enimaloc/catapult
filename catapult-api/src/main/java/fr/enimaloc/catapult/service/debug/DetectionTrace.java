package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Explains one detection cycle of a member in debug mode: which providers were asked, in priority order, what each
 * answered and why, what the cycle made of it and which binding it applied. Bound to the thread running the cycle
 * and recorded, when closed, as one exchange of the member's debug journal.
 * <p>
 * Every static method is a no-op when no trace is open on the current thread, which is always the case outside
 * debug mode: callers that would build details only for the trace check {@link #active()} first. Nothing here ever
 * throws into the detection.
 */
@Slf4j
public final class DetectionTrace implements AutoCloseable {

    static final String SERVICE = "detection";
    static final String METHOD = "CYCLE";
    static final String OPERATION = "detection.cycle";
    static final URI CYCLE_URI = URI.create("catapult://detection");
    static final String TIMEOUT = "timeout";

    private static final ThreadLocal<DetectionTrace> CURRENT = new ThreadLocal<>();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Scope NO_TRACE = () -> { };

    /** An {@link AutoCloseable} whose {@code close()} throws nothing. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private final UUID userId;
    private final DebugExchangeRecorder recorder;
    private final DetectionTrace previous;
    private final Instant at = Instant.now();
    private final long started = System.nanoTime();
    private final List<Map<String, Object>> providers = new ArrayList<>();
    private final Map<String, Object> binding = new LinkedHashMap<>();
    private Map<String, Object> outcome;
    private String error;
    private String pendingReason;
    private Map<String, Object> pendingDetails;

    private DetectionTrace(UUID userId, DebugExchangeRecorder recorder, DetectionTrace previous) {
        this.userId = userId;
        this.recorder = recorder;
        this.previous = previous;
    }

    /**
     * Opens a trace on the current thread when {@code userId} is in debug mode; else, or when that cannot be told,
     * returns a closeable that does nothing.
     */
    public static Scope openIfDebugged(DebugMemberRegistry registry, DebugExchangeRecorder recorder, UUID userId) {
        if (registry == null || recorder == null || userId == null) {
            return NO_TRACE;
        }
        try {
            if (registry.modeOf(userId).isEmpty()) {
                return NO_TRACE;
            }
        } catch (RuntimeException e) {
            log.warn("Could not tell whether {} is in debug mode: {}", userId, e.toString());
            return NO_TRACE;
        }
        return open(userId, recorder)::close;
    }

    /** Opens a trace for {@code userId} on the current thread; closing it records it and restores the previous one. */
    static DetectionTrace open(UUID userId, DebugExchangeRecorder recorder) {
        DetectionTrace trace = new DetectionTrace(userId, recorder, CURRENT.get());
        CURRENT.set(trace);
        return trace;
    }

    /** Whether a trace is open on the current thread. */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    /**
     * Notes what {@code provider} answered: {@code disabled}, {@code unavailable}, {@code stopped}, {@code found},
     * {@code none}, {@code error}, {@code timeout} or {@code not-asked}. The reason and details its getter gave
     * through {@link #reason} and {@link #detail} go with it, {@code reason} winning over the getter's.
     */
    public static void provider(String provider, String result, String reason, Map<String, Object> details) {
        DetectionTrace trace = CURRENT.get();
        if (trace == null) {
            return;
        }
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("provider", provider);
            entry.put("result", result);
            String why = reason != null ? reason : trace.pendingReason;
            if (why != null) {
                entry.put("reason", why);
            }
            Map<String, Object> all = new LinkedHashMap<>();
            if (trace.pendingDetails != null) {
                all.putAll(trace.pendingDetails);
            }
            putAll(all, details);
            if (!all.isEmpty()) {
                entry.put("details", all);
            }
            trace.providers.add(entry);
        } catch (RuntimeException e) {
            log.warn("Could not trace the {} answer: {}", provider, e.toString());
        } finally {
            trace.pendingReason = null;
            trace.pendingDetails = null;
        }
    }

    /** Why the provider being asked has nothing: called by its getter, written with the provider's entry. */
    public static void reason(String reason) {
        DetectionTrace trace = CURRENT.get();
        if (trace != null) {
            trace.pendingReason = reason;
        }
    }

    /** A detail about the provider being asked, written with its entry; a null value is left out. */
    public static void detail(String key, Object value) {
        DetectionTrace trace = CURRENT.get();
        if (trace == null || key == null || value == null) {
            return;
        }
        try {
            if (trace.pendingDetails == null) {
                trace.pendingDetails = new LinkedHashMap<>();
            }
            trace.pendingDetails.put(key, plain(value));
        } catch (RuntimeException e) {
            log.warn("Could not trace the detail {}: {}", key, e.toString());
        }
    }

    /**
     * What the cycle made of the answers: {@code frozen}, {@code timeout}, {@code unchanged}, {@code changed},
     * {@code unconfirmed} (no game this cycle, the known one kept until a second), {@code cleared} or {@code nothing}.
     * A timeout's {@code message} detail becomes the recorded exchange's error.
     */
    public static void outcome(String outcome, Map<String, Object> details) {
        DetectionTrace trace = CURRENT.get();
        if (trace == null) {
            return;
        }
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("outcome", outcome);
            putAll(entry, details);
            trace.outcome = entry;
            if (TIMEOUT.equals(outcome) && details != null && details.get("message") != null) {
                trace.error = String.valueOf(details.get("message"));
            }
        } catch (RuntimeException e) {
            log.warn("Could not trace the detection outcome: {}", e.toString());
        }
    }

    /** Adds what the binding step decided (custom binding guard, binding applied, category rule). */
    public static void binding(Map<String, Object> details) {
        DetectionTrace trace = CURRENT.get();
        if (trace == null) {
            return;
        }
        try {
            putAll(trace.binding, details);
        } catch (RuntimeException e) {
            log.warn("Could not trace the binding: {}", e.toString());
        }
    }

    /** Key/value pairs as a map, null values left out: {@code details("a", 1, "b", x)}. */
    public static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }

    /** Records the trace and unbinds it from the thread, whatever happens. */
    @Override
    public void close() {
        try {
            if (CURRENT.get() == this) {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            // No content type on purpose: the journal then masks the body as free text, which also catches secrets
            // quoted inside the reasons (exception messages, URLs)
            RawExchange raw = new RawExchange(at, METHOD, CYCLE_URI, null, JSON.writeValueAsBytes(body()), null,
                    null, null, error, elapsedMs);
            recorder.record(userId, Direction.OUT, raw, OPERATION, false, SERVICE);
        } catch (RuntimeException e) {
            log.warn("Could not record the detection trace of {}: {}", userId, e.toString());
        }
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!providers.isEmpty()) {
            body.put("providers", providers);
        }
        if (outcome != null) {
            body.put("outcome", outcome);
        }
        if (!binding.isEmpty()) {
            body.put("binding", binding);
        }
        return body;
    }

    private static void putAll(Map<String, Object> target, Map<String, ?> details) {
        if (details == null) {
            return;
        }
        details.forEach((k, v) -> {
            if (k != null && v != null) {
                target.put(k, plain(v));
            }
        });
    }

    /** A value JSON can hold as is: anything else is written as its name or its text. */
    private static Object plain(Object value) {
        return switch (value) {
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            case Enum<?> e -> e.name();
            case TemporalAccessor t -> t.toString();
            case Map<?, ?> m -> {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> {
                    if (k != null && v != null) {
                        copy.put(String.valueOf(k), plain(v));
                    }
                });
                yield copy;
            }
            case Collection<?> c -> c.stream().map(v -> v == null ? null : plain(v)).toList();
            default -> String.valueOf(value);
        };
    }
}
