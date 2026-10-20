package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;
import fr.enimaloc.catapult.service.ApiService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live updates for /channel/{username} over Server-Sent Events: every {@link ChannelUpdatedEvent}
 * published for a channel goes to that channel's open streams, named after the event's class
 * (e.g. {@code GameChangedEvent}) with the event itself as JSON data. channel-events.js patches
 * the page from it (see visibility.js) instead of re-fetching the fragment. Logged-in sessions
 * only, a few streams each (see {@link SessionStreams}), and only for a channel their member owns
 * or moderates (see {@link SessionChannels}): its events tell what the dashboard does. A channel is
 * named by its login, whatever its case. The streams are written by {@link SseStreams}, which also
 * closes them after a while and when their session logs out.
 */
@Slf4j
@RestController
public class ChannelEventStreamController {
    private final ApiService apiService;
    private final SseStreams sse;
    /** The open streams by channel login, lower case; a channel without any is not kept. */
    private final Map<String, List<SseEmitter>> emittersByUsername = new ConcurrentHashMap<>();
    private final SessionStreams streams;

    public ChannelEventStreamController(ApiService apiService, SseStreams sse) {
        this.apiService = apiService;
        this.sse = sse;
        streams = sse.sessionStreams("channel");
    }

    @GetMapping(value = "/events/channel/{username}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribe(@PathVariable String username, HttpServletRequest request) {
        HttpSession session = SessionStreams.loggedIn(request);
        if (session == null) {
            streams.refused("unauthenticated");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionChannels.canFollow(session, username, apiService)) {
            streams.refused("forbidden");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String sessionId = session.getId();
        String channel = key(username);
        SseEmitter emitter = SseStreams.emitter();
        // Added within compute: remove() drops an emptied list atomically too.
        emittersByUsername.compute(channel, (name, list) -> {
            List<SseEmitter> emitters = list == null ? new CopyOnWriteArrayList<>() : list;
            emitters.add(emitter);
            return emitters;
        });
        sse.open(emitter, () -> remove(sessionId, channel, emitter));
        streams.open(sessionId, emitter);
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(emitter);
    }

    @EventListener
    public void onChannelUpdated(ChannelUpdatedEvent event) {
        if (event.username() == null) return;
        String name = event.getClass().getSimpleName();
        for (SseEmitter emitter : emittersByUsername.getOrDefault(key(event.username()), List.of())) {
            sse.send(emitter, name, event);
        }
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private void remove(String sessionId, String channel, SseEmitter emitter) {
        streams.closed(sessionId, emitter);
        emittersByUsername.computeIfPresent(channel, (name, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        emittersByUsername.values()
                .stream()
                .flatMap(Collection::stream)
                .forEach(sse::close);
    }
}
