package fr.enimaloc.catapult.twitchat;

import fr.enimaloc.catapult.controller.AnonymousStreams;
import fr.enimaloc.catapult.controller.SessionStreams;
import fr.enimaloc.catapult.controller.SseStreams;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The open Twitchat relays, per account id: each Twitchat dispatch goes to its owner's pages
 * (/me/twitchat/events, a few per session, closed when it logs out) and OBS widgets
 * (/widget/obs/{token}/events, a few per address), never the public channel stream: its payloads
 * carry signed action tokens. Written by {@link SseStreams}.
 */
@Component
public class TwitchatDispatchHub {

    static final String EVENT = "TwitchatDispatch";

    private final SseStreams sse;
    private final SessionStreams pages;
    private final AnonymousStreams widgets;
    /** The open streams by owner; an owner without any is not kept. */
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public TwitchatDispatchHub(SseStreams sse) {
        this.sse = sse;
        pages = sse.sessionStreams("twitchat");
        widgets = sse.anonymousStreams("twitchat-widget");
    }

    /** A logged-in owner's page stream, counted against its session {@code sessionId}. */
    public SseEmitter subscribe(UUID userId, String sessionId) {
        SseEmitter emitter = SseStreams.emitter();
        add(userId, emitter, () -> pages.closed(sessionId, emitter));
        pages.open(sessionId, emitter);
        return emitter;
    }

    /**
     * An OBS widget's stream, counted against its address {@code ip}, closed after {@code timeout}
     * (its right to it can be revoked: its EventSource reconnects, and that right is checked again);
     * null when too many are open already.
     */
    public SseEmitter subscribeWidget(UUID userId, String ip, Duration timeout) {
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        if (!widgets.open(ip, emitter)) return null;
        add(userId, emitter, () -> widgets.closed(ip, emitter));
        return emitter;
    }

    private void add(UUID userId, SseEmitter emitter, Runnable onClosed) {
        emitters.compute(userId, (id, list) -> {
            List<SseEmitter> current = list == null ? new CopyOnWriteArrayList<>() : list;
            current.add(emitter);
            return current;
        });
        sse.open(emitter, () -> {
            onClosed.run();
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
