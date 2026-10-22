package fr.enimaloc.catapult.twitchat;

import fr.enimaloc.catapult.controller.AnonymousStreams;
import fr.enimaloc.catapult.controller.SessionStreams;
import fr.enimaloc.catapult.controller.SseStreams;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The open Twitchat relays, per account id: each Twitchat dispatch goes to its owner's pages
 * (/me/twitchat/events, a few per session, closed when it logs out) and OBS widgets
 * (/widget/obs/{token}/events, a few per address), never the public channel stream: its payloads
 * carry signed action tokens. Written by {@link SseStreams}.
 * <p>
 * Opening a stream, and a {@code NotificationDeliveryPending} for its owner ({@link #flush}), ask
 * catapult-api to hand the owner's pending Catapult notifications to Twitchat, on a background thread
 * so that the stream never waits for it or fails with it.
 */
@Slf4j
@Component
public class TwitchatDispatchHub {

    static final String EVENT = "TwitchatDispatch";

    private final SseStreams sse;
    private final SessionStreams pages;
    private final AnonymousStreams widgets;
    /** The open streams by owner; an owner without any is not kept. */
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** What a stream can flush its owner's notifications with: its session's JWT (read when needed) or its widget's token. */
    private record Credentials(Supplier<String> jwt, UUID widgetToken) {}

    private final NotificationFlusher flusher;
    private final Executor flushes;
    private final Map<SseEmitter, Credentials> credentials = new ConcurrentHashMap<>();
    /** How many flushes talk to catapult-api at once. */
    static final int MAX_FLUSHES = 8;
    private final java.util.concurrent.Semaphore limit = new java.util.concurrent.Semaphore(MAX_FLUSHES);
    /** The owners with a flush in flight, and those asked again meanwhile; guarded by {@code flushing}. */
    private final java.util.Set<UUID> flushing = new java.util.HashSet<>();
    private final java.util.Set<UUID> again = new java.util.HashSet<>();

    @Autowired
    public TwitchatDispatchHub(SseStreams sse, NotificationFlusher flusher) {
        this(sse, flusher, Executors.newVirtualThreadPerTaskExecutor());
    }

    TwitchatDispatchHub(SseStreams sse, NotificationFlusher flusher, Executor flushes) {
        this.flusher = flusher;
        this.flushes = flushes;
        this.sse = sse;
        pages = sse.sessionStreams("twitchat");
        widgets = sse.anonymousStreams("twitchat-widget");
    }

    /**
     * A logged-in owner's page stream, counted against their {@code session}. The session is kept to flush
     * with its JWT as it is then (it is renewed, and gone once the session is).
     */
    public SseEmitter subscribe(UUID userId, HttpSession session) {
        String sessionId = session.getId();
        SseEmitter emitter = SseStreams.emitter();
        credentials.put(emitter, new Credentials(() -> jwtOf(session), null));
        add(userId, emitter, () -> pages.closed(sessionId, emitter));
        pages.open(sessionId, emitter);
        flush(userId);
        return emitter;
    }

    /**
     * An OBS widget's stream, counted against its address {@code ip}, closed after {@code timeout}
     * (its right to it can be revoked: its EventSource reconnects, and that right is checked again);
     * null when too many are open already. {@code widgetToken} is what it flushes its owner's notifications with.
     */
    public SseEmitter subscribeWidget(UUID userId, UUID widgetToken, String ip, Duration timeout) {
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        if (!widgets.open(ip, emitter)) return null;
        credentials.put(emitter, new Credentials(null, widgetToken));
        add(userId, emitter, () -> widgets.closed(ip, emitter));
        flush(userId);
        return emitter;
    }

    /**
     * Asks catapult-api, in the background, to send the owner's pending notifications to their Twitchat, as
     * their page (its session's JWT) or else their OBS widget (its token) is open here; nothing when neither
     * is. At most one flush per owner is in flight: one asked meanwhile makes it run once more when it ends,
     * and at most {@link #MAX_FLUSHES} run at once. Another instance may flush too for its own streams: what
     * is delivered is deleted, so it is harmless.
     */
    public void flush(UUID userId) {
        if (emitters.getOrDefault(userId, List.of()).isEmpty()) return;
        synchronized (flushing) {
            if (!flushing.add(userId)) {
                again.add(userId);
                return;
            }
        }
        try {
            flushes.execute(() -> flushLoop(userId));
        } catch (RuntimeException e) {
            synchronized (flushing) {
                flushing.remove(userId);
                again.remove(userId);
            }
            log.warn("Could not schedule the notification flush of {}: {}", userId, e.toString());
        }
    }

    private void flushLoop(UUID userId) {
        boolean more;
        do {
            limit.acquireUninterruptibly();
            // Asked before it starts, a request is served by this very run.
            synchronized (flushing) {
                again.remove(userId);
            }
            try {
                flushNow(userId);
            } finally {
                limit.release();
            }
            synchronized (flushing) {
                more = again.remove(userId);
                if (!more) flushing.remove(userId);
            }
        } while (more);
    }

    /** The owner's credentials as they are now: streams and sessions come and go while a flush waits. */
    private void flushNow(UUID userId) {
        try {
            List<SseEmitter> open = emitters.getOrDefault(userId, List.of());
            List<Credentials> held = open.stream().map(credentials::get).filter(Objects::nonNull).toList();
            UUID widget = held.stream().map(Credentials::widgetToken).filter(Objects::nonNull).findFirst().orElse(null);
            boolean done = false;
            for (Credentials c : held) {
                String token = c.jwt() == null ? null : c.jwt().get();
                if (token != null && ok(flusher.flushPage(token))) {
                    done = true;
                    break;
                }
            }
            // The page's JWT may have expired (or its session ended) while its stream stayed open.
            if (!done && widget != null) flusher.flushWidget(widget);
        } catch (RuntimeException e) {
            log.warn("Notification flush of {} failed: {}", userId, e.toString());
        }
    }

    private static boolean ok(int status) {
        return status >= 200 && status < 300;
    }

    private static String jwtOf(HttpSession session) {
        try {
            return session.getAttribute(ApiClient.SESSION_JWT_KEY) instanceof String jwt ? jwt : null;
        } catch (IllegalStateException e) {
            return null; // the session ended
        }
    }

    private void add(UUID userId, SseEmitter emitter, Runnable onClosed) {
        emitters.compute(userId, (id, list) -> {
            List<SseEmitter> current = list == null ? new CopyOnWriteArrayList<>() : list;
            current.add(emitter);
            return current;
        });
        sse.open(emitter, () -> {
            onClosed.run();
            credentials.remove(emitter);
            remove(userId, emitter);
        });
    }

    /** {@code dispatchJson}: a TwitchatDispatch as JSON, sent as is. */
    public void publish(UUID userId, String dispatchJson) {
        for (SseEmitter emitter : emitters.getOrDefault(userId, List.of())) {
            sse.send(emitter, SseEmitter.event().name(EVENT).data(dispatchJson));
        }
    }

    int subscriberCount(UUID userId) {
        return emitters.getOrDefault(userId, List.of()).size();
    }

    private void remove(UUID userId, SseEmitter emitter) {
        emitters.computeIfPresent(userId, (id, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
