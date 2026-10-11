package fr.enimaloc.catapult.twitchat;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The open /me/twitchat/events streams, per account id: each Twitchat dispatch goes to its
 * owner's pages only (never the public channel stream: its payloads carry signed action tokens).
 */
@Component
public class TwitchatDispatchHub {

    static final String EVENT = "TwitchatDispatch";

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));
        return emitter;
    }

    /** {@code dispatchJson}: a TwitchatDispatch as JSON, sent as is. */
    public void publish(UUID userId, String dispatchJson) {
        for (SseEmitter emitter : emitters.getOrDefault(userId, List.of())) {
            try {
                emitter.send(SseEmitter.event().name(EVENT).data(dispatchJson));
            } catch (IOException | IllegalStateException e) {
                remove(userId, emitter);
            }
        }
    }

    int subscriberCount(UUID userId) {
        return emitters.getOrDefault(userId, List.of()).size();
    }

    private void remove(UUID userId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(userId);
        if (list != null) list.remove(emitter);
    }
}
