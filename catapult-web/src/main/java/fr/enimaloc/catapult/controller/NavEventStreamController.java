package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.NavChannelsChangedEvent;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
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
 * session: {@code NavChannelLive} ({@code {username, live}}) when one of the channels it lists
 * goes live or offline, {@code NavChannelsChanged} when the list itself changed (spa.js then
 * reloads it). Which channels a session lists is what /spa/nav-channels last served it.
 */
@RestController
public class NavEventStreamController {
    static final String LIVE = "NavChannelLive";
    static final String CHANGED = "NavChannelsChanged";

    /** A {@link #LIVE} event's data. */
    record NavChannelLive(String username, boolean live) {}

    /** What a session's navbar shows, and its open streams. */
    private static final class Nav {
        volatile List<ChannelDto> channels = List.of();
        final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    }

    private final Map<String, Nav> navsBySession = new ConcurrentHashMap<>();

    @GetMapping(value = "/events/nav", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(HttpSession session) {
        String sessionId = session.getId();
        SseEmitter emitter = new SseEmitter(0L);
        navsBySession.computeIfAbsent(sessionId, ignored -> new Nav()).emitters.add(emitter);
        emitter.onCompletion(() -> remove(sessionId, emitter));
        emitter.onTimeout(() -> remove(sessionId, emitter));
        emitter.onError(e -> remove(sessionId, emitter));
        return emitter;
    }

    /** The channels {@code sessionId}'s navbar now lists (see SpaFragmentController#navChannels). */
    public void remember(String sessionId, List<ChannelDto> channels) {
        navsBySession.computeIfAbsent(sessionId, ignored -> new Nav()).channels = List.copyOf(channels);
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
        navsBySession.values().forEach(nav -> nav.channels.stream().filter(which).forEach(channel -> {
            nav.channels = nav.channels.stream().map(c -> c == channel
                    ? new ChannelDto(c.id(), c.twitchId(), c.twitchUsername(), c.profileImageUrl(), live) : c).toList();
            send(nav, LIVE, new NavChannelLive(channel.twitchUsername(), live));
        }));
    }

    private static void send(Nav nav, String name, Object data) {
        for (SseEmitter emitter : nav.emitters) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (IOException | IllegalStateException e) {
                nav.emitters.remove(emitter);
            }
        }
    }

    private void remove(String sessionId, SseEmitter emitter) {
        navsBySession.computeIfPresent(sessionId, (id, nav) -> {
            nav.emitters.remove(emitter);
            return nav.emitters.isEmpty() ? null : nav;
        });
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        navsBySession.values().forEach(nav -> nav.emitters.forEach(SseEmitter::complete));
    }
}
