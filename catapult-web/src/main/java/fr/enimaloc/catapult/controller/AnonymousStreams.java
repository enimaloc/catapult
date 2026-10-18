package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounds the SSE streams opened without a session, which anyone may open: at most
 * {@link #MAX_PER_IP} per address (a new one beyond that closing its oldest), {@link #MAX_TOTAL}
 * overall (beyond that refused), each closed after {@link #TIMEOUT} (the browser reconnects).
 * The counterpart of {@link SessionStreams} for visitors who are not logged in.
 * <p>
 * They show in {@link InteractionMetrics} under the stream {@value #STREAM}: open, refused for
 * {@code capacity}, closed for a newer one of the same address.
 */
final class AnonymousStreams {
    static final int MAX_PER_IP = 8;
    static final int MAX_TOTAL = 1000;
    static final Duration TIMEOUT = Duration.ofMinutes(30);
    static final String STREAM = "nav-anonymous";

    /** Each address's streams, oldest first; only touched within the map's compute calls. */
    private final Map<String, Deque<SseEmitter>> byIp = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final InteractionMetrics metrics;

    AnonymousStreams(InteractionMetrics metrics) {
        this.metrics = metrics;
        metrics.streams(STREAM, total::get);
    }

    /**
     * Counts {@code emitter} against {@code ip}, closing that address's oldest stream past its limit;
     * false when {@link #MAX_TOTAL} streams are already open, {@code emitter} then not counted.
     */
    boolean open(String ip, SseEmitter emitter) {
        if (total.incrementAndGet() > MAX_TOTAL) {
            total.decrementAndGet();
            metrics.streamRefused(STREAM, "capacity");
            return false;
        }
        List<SseEmitter> evicted = new ArrayList<>();
        byIp.compute(ip, (key, streams) -> {
            Deque<SseEmitter> current = streams == null ? new ArrayDeque<>() : streams;
            current.addLast(emitter);
            while (current.size() > MAX_PER_IP) evicted.add(current.pollFirst());
            return current;
        });
        // Completed outside compute: its completion callback comes back to closed().
        for (SseEmitter oldest : evicted) {
            total.decrementAndGet();
            oldest.complete();
            metrics.streamEvicted(STREAM);
        }
        return true;
    }

    /** {@code emitter} closed; counted once however often it is reported. */
    void closed(String ip, SseEmitter emitter) {
        AtomicBoolean removed = new AtomicBoolean();
        byIp.computeIfPresent(ip, (key, streams) -> {
            removed.set(streams.remove(emitter));
            return streams.isEmpty() ? null : streams;
        });
        if (removed.get()) total.decrementAndGet();
    }
}
