package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.cluster.InstanceBus;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.DegradedStateChangedEvent;
import fr.enimaloc.catapult.event.NavChannelsChangedEvent;
import fr.enimaloc.catapult.event.notification.BroadcastStreamEvent;
import fr.enimaloc.catapult.event.notification.NotificationStreamEvent;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.degraded.DegradedState;
import fr.enimaloc.catapult.service.degraded.DegradedView;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The live stream every page opens, over Server-Sent Events, logged in or not:
 * <ul>
 *     <li>to a logged-in session's pages, its navbar's channels: {@code NavChannelLive}
 *     ({@code {username, live}}) when one of the channels it lists goes live or offline,
 *     {@code NavChannelsChanged} when the list itself changed (spa.js then reloads it). Which
 *     channels a session lists is what /spa/nav-channels last served it (see {@link SessionChannels});</li>
 *     <li>to those same pages, their account's notifications ({@code NotificationCreated},
 *     {@code NotificationRead}, {@code NotificationsAllRead}, {@code NotificationDeleted}), never
 *     another account's nor a logged-out page's;</li>
 *     <li>to every page, catapult-api's broadcasts as {@code Broadcast} ({@code {name, data}}): a
 *     stream opening during an announced maintenance gets it first (see {@link BroadcastState}), and
 *     every stream gets {@code maintenance.imminent} when this server shuts down;</li>
 *     <li>to every page, whether catapult runs degraded as {@code Degraded} ({@link DegradedView}): sent
 *     when a stream opens, even when it does not, and again whenever it changes.</li>
 * </ul>
 * A session keeps at most a few streams ({@link SessionStreams}); visitors' streams are bounded per
 * address and overall, and closed after a while ({@link AnonymousStreams}): past the overall bound
 * the answer is 503.
 * <p>
 * Behind a proxy: the response asks it not to buffer the stream ({@code X-Accel-Buffering: no}); the
 * streams are written by {@link SseStreams} (off the publisher's thread, with heartbeats, closed after a
 * while), a stream that cannot take an event being dropped.
 */
@Slf4j
@RestController
public class NavEventStreamController {
    static final String LIVE = "NavChannelLive";
    static final String CHANGED = "NavChannelsChanged";
    static final String BROADCAST = "Broadcast";
    static final String DEGRADED = "Degraded";
    static final String IMMINENT = "maintenance.imminent";

    /** A {@link #LIVE} event's data. */
    record NavChannelLive(String username, boolean live) {}

    /** A {@link #BROADCAST} event's data: catapult-api's broadcast name and data. */
    record Broadcast(String name, Map<String, Object> data) {}

    /**
     * A session with streams open (its id kept: an invalidated session may not tell it), the account it was logged in as then (null if unknown), the token
     * of that login (a login in the same session replaces it), the channels its navbar lists (kept here, not
     * written back to the session from outside its requests: it may be gone, or served by another instance),
     * and those streams.
     */
    private record Nav(String sessionId, HttpSession session, UUID account, String token,
                       AtomicReference<List<ChannelDto>> channels, List<SseEmitter> emitters) {}

    /** What {@link #remember} tells every instance: a session's navbar now lists {@code channels}. */
    record Remembered(String sessionId, List<ChannelDto> channels) {}

    static final String REMEMBER_TOPIC = "nav.channels";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Only the sessions with a stream open. */
    private final Map<String, Nav> navsBySession = new ConcurrentHashMap<>();
    /** The streams opened without a session, and the address each counts against. */
    private final Map<SseEmitter, String> anonymous = new ConcurrentHashMap<>();
    private final SseStreams sse;
    private final SessionStreams streams;
    private final AnonymousStreams anonymousStreams;
    private final ApiService apiService;
    private final BroadcastState broadcastState;
    /** Tells the other instances what a navbar lists; null alone (a test). */
    private final InstanceBus bus;
    /** Tells whether catapult runs degraded; null alone (a test). */
    private final DegradedState degradedState;
    /** Makes the streams, by timeout; tests swap it for one whose writes can be made to fail. */
    LongFunction<SseEmitter> emitters = SseEmitter::new;

    public NavEventStreamController(SseStreams sse, ApiService apiService, BroadcastState broadcastState,
                                    ObjectProvider<InstanceBus> bus, ObjectProvider<DegradedState> degradedState) {
        this.sse = sse;
        this.bus = bus.getIfAvailable();
        this.degradedState = degradedState.getIfAvailable();
        if (this.bus != null) this.bus.on(REMEMBER_TOPIC, this::remembered);
        streams = sse.sessionStreams("nav");
        anonymousStreams = sse.anonymousStreams(AnonymousStreams.STREAM);
        this.apiService = apiService;
        this.broadcastState = broadcastState;
    }

    @GetMapping(value = "/events/nav", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribe(HttpServletRequest request) {
        HttpSession session = SessionStreams.loggedIn(request);
        SseEmitter emitter = session == null ? subscribeAnonymous(request.getRemoteAddr()) : subscribe(session);
        if (emitter == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        // SseStreams writes a comment at once: the headers only go out with the first write, and the
        // page only sees the stream open (live.js's onOpen, lifting the restart veil) once they do.
        broadcastState.maintenance().ifPresent(maintenance ->
                sse.send(emitter, BROADCAST, new Broadcast(BroadcastState.SCHEDULED, maintenance)));
        DegradedView degraded = degradedState == null ? null : degradedState.current();
        if (degraded != null) sse.send(emitter, DEGRADED, degraded);
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(emitter);
    }

    private SseEmitter subscribe(HttpSession session) {
        String sessionId = session.getId();
        UUID account = apiService.currentUserId();
        String token = tokenOf(session);
        SseEmitter emitter = emitters.apply(SseStreams.TIMEOUT.toMillis());
        List<SseEmitter> former = new ArrayList<>();
        // Added within compute: remove() drops an emptied Nav atomically too. A session now logged in
        // again drops the streams of the former login, which must not get the new one's events.
        navsBySession.compute(sessionId, (id, nav) -> {
            Nav current = nav;
            if (current == null || !Objects.equals(current.account(), account) || !Objects.equals(current.token(), token)) {
                if (current != null) former.addAll(current.emitters());
                current = new Nav(sessionId, session, account, token, new AtomicReference<>(listed(session)),
                        new CopyOnWriteArrayList<>());
            }
            current.emitters().add(emitter);
            return current;
        });
        former.forEach(sse::close);
        sse.open(emitter, () -> remove(sessionId, emitter));
        streams.open(sessionId, emitter);
        return emitter;
    }

    /** A visitor's stream; null when too many are open already. */
    private SseEmitter subscribeAnonymous(String ip) {
        SseEmitter emitter = emitters.apply(AnonymousStreams.TIMEOUT.toMillis());
        if (!anonymousStreams.open(ip, emitter)) {
            return null;
        }
        anonymous.put(emitter, ip);
        sse.open(emitter, () -> removeAnonymous(ip, emitter));
        return emitter;
    }

    /**
     * The channels {@code session}'s navbar now lists (see SpaFragmentController#navChannels): kept in the
     * session for its streams to come, and told to its streams already open, wherever they are. A session
     * invalidated meanwhile keeps nothing.
     */
    public void remember(HttpSession session, List<ChannelDto> channels) {
        String sessionId;
        try {
            SessionChannels.remember(session, channels);
            sessionId = session.getId();
        } catch (IllegalStateException e) {
            return; // invalidated meanwhile
        }
        if (bus == null) {
            remembered(sessionId, List.copyOf(channels));
        } else {
            bus.publish(REMEMBER_TOPIC, JSON.writeValueAsString(new Remembered(sessionId, channels)));
        }
    }

    private void remembered(String message) {
        Remembered remembered = JSON.readValue(message, Remembered.class);
        remembered(remembered.sessionId(), remembered.channels() == null ? List.of() : List.copyOf(remembered.channels()));
    }

    private void remembered(String sessionId, List<ChannelDto> channels) {
        Nav nav = navsBySession.get(sessionId);
        if (nav != null) nav.channels().set(channels);
    }

    /** What {@code session} lists, within its own request; none once it is gone. */
    private static List<ChannelDto> listed(HttpSession session) {
        try {
            return SessionChannels.of(session);
        } catch (IllegalStateException e) {
            return List.of();
        }
    }

    /** A channel goes live or offline, by login (ChannelEventRedisBridge, or the mock's MockAdminController). */
    @EventListener
    public void onChannelLive(ChannelLiveStateEvent event) {
        live(channel -> channel.twitchUsername().equalsIgnoreCase(event.username()), event.state());
    }

    @EventListener
    public void onChannelsChanged(NavChannelsChangedEvent event) {
        Nav nav = navsBySession.get(event.sessionId());
        if (nav != null) {
            send(nav, CHANGED, Map.of());
        }
    }

    /** catapult-api's broadcasts (NotificationRedisBridge), or the mock's: to every stream. */
    @EventListener
    public void onBroadcast(BroadcastStreamEvent event) {
        broadcastState.accept(event.name(), event.data());
        broadcastToAll(new Broadcast(event.name(), event.data()));
    }

    /** catapult runs degraded, or no longer (DegradedState): to every stream. */
    @EventListener
    public void onDegraded(DegradedStateChangedEvent event) {
        toAll(() -> SseEmitter.event().name(DEGRADED).data(event.view()));
    }

    /** An account's notification changes: to the streams of the sessions still logged in as it, by the same login. */
    @EventListener
    public void onNotification(NotificationStreamEvent event) {
        if (event.accountId() == null) return;
        navsBySession.values().stream()
                .filter(nav -> event.accountId().equals(nav.account()))
                .forEach(nav -> isolated(nav, () -> {
                    if (stillLoggedIn(nav)) send(nav, event.name(), event.data());
                }));
    }

    private void broadcastToAll(Broadcast broadcast) {
        toAll(() -> SseEmitter.event().name(BROADCAST).data(broadcast));
    }

    /** {@code event} (a new one per stream: a builder is not reusable) to every stream. */
    private void toAll(Supplier<SseEmitter.SseEventBuilder> event) {
        navsBySession.values().forEach(nav -> send(nav, event));
        anonymous.keySet().forEach(emitter -> sse.send(emitter, event.get()));
    }

    private void live(Predicate<ChannelDto> which, boolean live) {
        navsBySession.values().forEach(nav -> isolated(nav, () -> {
            if (tokenOf(nav.session()) == null) {
                // Logged out or gone: its streams close (their pages reconnect as what the session is now).
                nav.emitters().forEach(sse::close);
                return;
            }
            List<ChannelDto> channels = nav.channels().get();
            if (channels.stream().noneMatch(which)) return;
            nav.channels().set(channels.stream().map(c -> which.test(c)
                    ? new ChannelDto(c.id(), c.twitchId(), c.twitchUsername(), c.profileImageUrl(), live) : c).toList());
            channels.stream().filter(which)
                    .forEach(channel -> send(nav, LIVE, new NavChannelLive(channel.twitchUsername(), live)));
        }));
    }

    /** {@code action} for {@code nav} alone: whatever goes wrong with one session, the others still get theirs. */
    private void isolated(Nav nav, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Dropped an event for a navbar's session: {}", e.toString());
        }
    }

    /**
     * Whether {@code nav}'s session still holds the login its streams were opened with; if not
     * (logged out, or logged in again, possibly as another account), its streams close. The token
     * is compared rather than its subject: the mock's tokens are not JWTs.
     */
    private boolean stillLoggedIn(Nav nav) {
        String token = tokenOf(nav.session());
        boolean same = token != null && token.equals(nav.token());
        if (!same) nav.emitters().forEach(sse::close);
        return same;
    }

    /** The login token {@code session} holds; null without one or once invalidated. */
    private static String tokenOf(HttpSession session) {
        try {
            return session.getAttribute(ApiClient.SESSION_JWT_KEY) instanceof String token ? token : null;
        } catch (IllegalStateException e) {
            return null; // invalidated
        }
    }

    private void send(Nav nav, String name, Object data) {
        send(nav, () -> SseEmitter.event().name(name).data(data));
    }

    private void send(Nav nav, Supplier<SseEmitter.SseEventBuilder> event) {
        nav.emitters().forEach(emitter -> sse.send(emitter, event.get()));
    }

    private void remove(String sessionId, SseEmitter emitter) {
        streams.closed(sessionId, emitter);
        navsBySession.computeIfPresent(sessionId, (id, nav) -> {
            nav.emitters().remove(emitter);
            return nav.emitters().isEmpty() ? null : nav;
        });
    }

    private void removeAnonymous(String ip, SseEmitter emitter) {
        anonymous.remove(emitter);
        anonymousStreams.closed(ip, emitter);
    }

    /** Every page is told this server is going away (no ETA known), then its stream closes. */
    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        broadcastToAll(new Broadcast(IMMINENT, Collections.singletonMap("etaSeconds", null)));
        navsBySession.values().forEach(nav -> nav.emitters().forEach(sse::close));
        anonymous.keySet().forEach(sse::close);
    }
}
