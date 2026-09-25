package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.ws.event.ChannelUpdatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live updates for /channel/{username} over an SSE stream instead of a WebSocket — same
 * subscribe-by-username / fan-out-on-event shape catapult-api's own ConnectionEventService
 * already uses for its push events. The stream carries a bare "update" event with no
 * payload; the client reacts by re-fetching {@code /spa/channel/{username}} the same way
 * every mutation on this page already does on its own success.
 */
@Slf4j
@RestController
public class ChannelEventStreamController {
    private final Map<String, List<SseEmitter>> emittersByUsername = new ConcurrentHashMap<>();

    @GetMapping(value = "/events/channel/{username}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String username) {
        SseEmitter emitter = new SseEmitter(0L);
        emittersByUsername.computeIfAbsent(username, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(username, emitter));
        emitter.onTimeout(() -> remove(username, emitter));
        emitter.onError(e -> remove(username, emitter));
        return emitter;
    }

    @EventListener
    public void onChannelUpdated(ChannelUpdatedEvent event) {
        List<SseEmitter> emitters = emittersByUsername.getOrDefault(event.getUsername(), List.of());
        List<SseEmitter> dead = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("update").data(""));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            emittersByUsername.getOrDefault(event.getUsername(), new CopyOnWriteArrayList<>()).removeAll(dead);
        }
    }

    private void remove(String username, SseEmitter emitter) {
        List<SseEmitter> list = emittersByUsername.get(username);
        if (list != null) {
            list.remove(emitter);
        }
    }
}
