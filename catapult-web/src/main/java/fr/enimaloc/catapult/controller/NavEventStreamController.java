package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.NavChannelsChangedEvent;
import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Live updates for the navbar's channels over Server-Sent Events, one stream per open page of a
 * logged-in session: {@code NavChannelLive} ({@code {username, live}}) when one of the channels it
 * lists goes live or offline, {@code NavChannelsChanged} when the list itself changed (spa.js then
 * reloads it). Which channels a session lists is what /spa/nav-channels last served it (see
 * {@link SessionChannels}).
 */
@RestController
public class NavEventStreamController {
    static final String LIVE = "NavChannelLive";
    static final String CHANGED = "NavChannelsChanged";

    /** A {@link #LIVE} event's data. */
    record NavChannelLive(String username, boolean live) {}

    /** A session with streams open, and those streams. */
    private record Nav(HttpSession session, List<SseEmitter> emitters) {}

    /** Only the sessions with a stream open. */
    private final Map<String, Nav> navsBySession = new ConcurrentHashMap<>();
    private final SessionStreams streams;

    public NavEventStreamController(InteractionMetrics metrics) {
        streams = new SessionStreams("nav", metrics);
    }

    @GetMapping(value = "/events/nav", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribe(HttpServletRequest request) {
        HttpSession session = SessionStreams.loggedIn(request);
        if (session == null) {
            streams.refused("unauthenticated");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String sessionId = session.getId();
        SseEmitter emitter = new SseEmitter(0L);
        // Added within compute: remove() drops an emptied Nav atomically too.
        navsBySession.compute(sessionId, (id, nav) -> {
            Nav current = nav == null ? new Nav(session, new CopyOnWriteArrayList<>()) : nav;
            current.emitters().add(emitter);
            return current;
        });
        emitter.onCompletion(() -> remove(sessionId, emitter));
        emitter.onTimeout(() -> remove(sessionId, emitter));
        emitter.onError(e -> remove(sessionId, emitter));
        streams.open(sessionId, emitter);
        return ResponseEntity.ok(emitter);
    }

    /** The channels {@code session}'s navbar now lists (see SpaFragmentController#navChannels). */
    public void remember(HttpSession session, List<ChannelDto> channels) {
        SessionChannels.remember(session, channels);
    }

    /** The mock's channels go live by name (MockAdminController). */
    @EventListener
    public void onChannelLive(ChannelLiveStateEvent event) {
        live(channel -> channel.twitchUsername().equals(event.username()), event.state());
    }

    /** catapult-api's own channels go live by account id (ChannelEventRedisBridge). */
    public void liveChanged(UUID channelId, boolean live) {
        live(channel -> channel.id().equals(channelId), live);
    }

    @EventListener
    public void onChannelsChanged(NavChannelsChangedEvent event) {
        Nav nav = navsBySession.get(event.sessionId());
        if (nav != null) {
            send(nav, CHANGED, Map.of());
        }
    }

    private void live(Predicate<ChannelDto> which, boolean live) {
        navsBySession.values().forEach(nav -> {
            List<ChannelDto> channels = channelsOf(nav);
            if (channels.stream().noneMatch(which)) return;
            SessionChannels.remember(nav.session(), channels.stream().map(c -> which.test(c)
                    ? new ChannelDto(c.id(), c.twitchId(), c.twitchUsername(), c.profileImageUrl(), live) : c).toList());
            channels.stream().filter(which)
                    .forEach(channel -> send(nav, LIVE, new NavChannelLive(channel.twitchUsername(), live)));
        });
    }

    /** What {@code nav}'s session lists; none once that session is gone, whose streams then close. */
    private static List<ChannelDto> channelsOf(Nav nav) {
        try {
            return SessionChannels.of(nav.session());
        } catch (IllegalStateException e) {
            nav.emitters().forEach(SseEmitter::complete);
            return List.of();
        }
    }

    private static void send(Nav nav, String name, Object data) {
        for (SseEmitter emitter : nav.emitters()) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (IOException | IllegalStateException e) {
                nav.emitters().remove(emitter);
            }
        }
    }

    private void remove(String sessionId, SseEmitter emitter) {
        streams.closed(sessionId, emitter);
        navsBySession.computeIfPresent(sessionId, (id, nav) -> {
            nav.emitters().remove(emitter);
            return nav.emitters().isEmpty() ? null : nav;
        });
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        navsBySession.values().forEach(nav -> nav.emitters().forEach(SseEmitter::complete));
    }
}
