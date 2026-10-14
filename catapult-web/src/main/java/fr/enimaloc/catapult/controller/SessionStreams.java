package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bounds a controller's open SSE streams: only a logged-in session may open one, and at most
 * {@link #MAX_PER_SESSION} of them (its tabs), a new one beyond that closing the session's oldest.
 * An endless stream holds a connection and its state on the server: unbounded, any visitor could
 * open them until it runs out.
 * <p>
 * Its streams, the newer ones closing older and those turned away, show in {@link InteractionMetrics}
 * under the stream's name.
 */
final class SessionStreams {
    static final int MAX_PER_SESSION = 8;

    private final Map<String, Deque<SseEmitter>> bySession = new ConcurrentHashMap<>();
    private final String stream;
    private final InteractionMetrics metrics;

    SessionStreams(String stream, InteractionMetrics metrics) {
        this.stream = stream;
        this.metrics = metrics;
        metrics.streams(stream, () -> bySession.values().stream().mapToInt(Deque::size).sum());
    }

    /** The calling request's session when it is logged in; null otherwise, never creating one. */
    static HttpSession loggedIn(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        try {
            return session != null && session.getAttribute(ApiClient.SESSION_JWT_KEY) != null ? session : null;
        } catch (IllegalStateException e) {
            return null; // invalidated meanwhile
        }
    }

    /** Counts {@code emitter} against {@code sessionId}, closing its oldest stream past the limit. */
    void open(String sessionId, SseEmitter emitter) {
        Deque<SseEmitter> streams = bySession.computeIfAbsent(sessionId, ignored -> new ConcurrentLinkedDeque<>());
        streams.addLast(emitter);
        while (streams.size() > MAX_PER_SESSION) {
            SseEmitter oldest = streams.pollFirst();
            if (oldest != null) {
                oldest.complete();
                metrics.streamEvicted(stream);
            }
        }
    }

    /** A stream turned away: {@code unauthenticated} (logged out) or {@code forbidden}. */
    void refused(String reason) {
        metrics.streamRefused(stream, reason);
    }

    void closed(String sessionId, SseEmitter emitter) {
        bySession.computeIfPresent(sessionId, (id, streams) -> {
            streams.remove(emitter);
            return streams.isEmpty() ? null : streams;
        });
    }
}
