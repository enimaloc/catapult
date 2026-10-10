package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live updates for /channel/{username} over Server-Sent Events: every {@link ChannelUpdatedEvent}
 * published for a channel goes to that channel's open streams, named after the event's class
 * (e.g. {@code GameChangedEvent}) with the event itself as JSON data. channel-events.js patches
 * the page from it (see visibility.js) instead of re-fetching the fragment.
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
        List<SseEmitter> emitters = emittersByUsername.getOrDefault(event.username(), List.of());
        List<SseEmitter> dead = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event.getClass().getSimpleName()).data(event));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            emittersByUsername.getOrDefault(event.username(), new CopyOnWriteArrayList<>()).removeAll(dead);
        }
    }

    private void remove(String username, SseEmitter emitter) {
        List<SseEmitter> list = emittersByUsername.get(username);
        if (list != null) {
            list.remove(emitter);
        }
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        emittersByUsername.values()
                .stream()
                .flatMap(Collection::stream)
                .forEach(ResponseBodyEmitter::complete);
    }
}
