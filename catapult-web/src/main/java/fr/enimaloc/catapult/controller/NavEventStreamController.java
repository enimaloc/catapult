package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.NavChannelsChangedEvent;
import fr.enimaloc.catapult.event.notification.BroadcastStreamEvent;
import fr.enimaloc.catapult.event.notification.NotificationStreamEvent;
import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *     every stream gets {@code maintenance.imminent} when this server shuts down.</li>
 * </ul>
 * A session keeps at most a few streams ({@link SessionStreams}); visitors' streams are bounded per
 * address and overall, and closed after a while ({@link AnonymousStreams}): past the overall bound
 * the answer is 503.
 * <p>
 * Behind nginx: the response asks it not to buffer the stream ({@code X-Accel-Buffering: no}), and
 * every open stream gets a comment on opening and every {@link #HEARTBEAT_MILLIS} ms so that an idle one is not cut
 * by the proxy's read timeout; a stream that cannot take it is dropped as after any failed send.
 */
@RestController
public class NavEventStreamController {
    static final String LIVE = "NavChannelLive";
    static final String CHANGED = "NavChannelsChanged";
    static final String BROADCAST = "Broadcast";
    static final String IMMINENT = "maintenance.imminent";
    /** Below nginx's default 60 s read timeout. */
    static final long HEARTBEAT_MILLIS = 30_000;
    private static final String HEARTBEAT = "heartbeat";

    /** A {@link #LIVE} event's data. */
    record NavChannelLive(String username, boolean live) {}

    /** A {@link #BROADCAST} event's data: catapult-api's broadcast name and data. */
    record Broadcast(String name, Map<String, Object> data) {}

    /**
     * A session with streams open (its id kept: an invalidated session may not tell it), the account it was logged in as then (null if unknown), the token
     * of that login (a login in the same session replaces it), and those streams.
     */
    private record Nav(String sessionId, HttpSession session, UUID account, String token, List<SseEmitter> emitters) {}

    /** Only the sessions with a stream open. */
    private final Map<String, Nav> navsBySession = new ConcurrentHashMap<>();
    /** The streams opened without a session, and the address each counts against. */
    private final Map<SseEmitter, String> anonymous = new ConcurrentHashMap<>();
    private final SessionStreams streams;
    private final AnonymousStreams anonymousStreams;
    private final ApiService apiService;
    private final BroadcastState broadcastState;
    /** Makes the streams, by timeout; tests swap it for one whose writes can be made to fail. */
    LongFunction<SseEmitter> emitters = SseEmitter::new;

    public NavEventStreamController(InteractionMetrics metrics, ApiService apiService, BroadcastState broadcastState) {
        streams = new SessionStreams("nav", metrics);
        anonymousStreams = new AnonymousStreams(metrics);
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
        broadcastState.maintenance().ifPresent(maintenance ->
                send(emitter, BROADCAST, new Broadcast(BroadcastState.SCHEDULED, maintenance)));
        // Something written at once: the headers only go out with the first write, and the page
        // only sees the stream open (live.js's onOpen, lifting the restart veil) once they do.
        send(emitter, SseEmitter.event().comment(HEARTBEAT));
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(emitter);
    }

    private SseEmitter subscribe(HttpSession session) {
        String sessionId = session.getId();
        UUID account = apiService.currentUserId();
        String token = tokenOf(session);
        SseEmitter emitter = emitters.apply(0L);
        List<SseEmitter> former = new ArrayList<>();
        // Added within compute: remove() drops an emptied Nav atomically too. A session now logged in
        // again drops the streams of the former login, which must not get the new one's events.
        navsBySession.compute(sessionId, (id, nav) -> {
            Nav current = nav;
            if (current == null || !Objects.equals(current.account(), account) || !Objects.equals(current.token(), token)) {
                if (current != null) former.addAll(current.emitters());
                current = new Nav(sessionId, session, account, token, new CopyOnWriteArrayList<>());
            }
            current.emitters().add(emitter);
            return current;
        });
        former.forEach(SseEmitter::complete);
        emitter.onCompletion(() -> remove(sessionId, emitter));
        emitter.onTimeout(() -> remove(sessionId, emitter));
        emitter.onError(e -> remove(sessionId, emitter));
        streams.open(sessionId, emitter);
        return emitter;
    }

    /** A visitor's stream; null when too many are open already. */
    private SseEmitter subscribeAnonymous(String ip) {
        SseEmitter emitter = emitters.apply(AnonymousStreams.TIMEOUT.toMillis());
        anonymous.put(emitter, ip);
        emitter.onCompletion(() -> removeAnonymous(ip, emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> removeAnonymous(ip, emitter));
        if (!anonymousStreams.open(ip, emitter)) {
            anonymous.remove(emitter);
            return null;
        }
        return emitter;
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

    /** catapult-api's broadcasts (NotificationRedisBridge), or the mock's: to every stream. */
    @EventListener
    public void onBroadcast(BroadcastStreamEvent event) {
        broadcastState.accept(event.name(), event.data());
        broadcastToAll(new Broadcast(event.name(), event.data()));
    }

    /** An account's notification changes: to the streams of the sessions still logged in as it, by the same login. */
    @EventListener
    public void onNotification(NotificationStreamEvent event) {
        if (event.accountId() == null) return;
        navsBySession.values().stream()
                .filter(nav -> event.accountId().equals(nav.account()))
                .filter(NavEventStreamController::stillLoggedIn)
                .forEach(nav -> send(nav, event.name(), event.data()));
    }

    /** A comment to every open stream, one task for them all; those it cannot reach are dropped. */
    @Scheduled(fixedRate = HEARTBEAT_MILLIS, initialDelay = HEARTBEAT_MILLIS)
    public void heartbeat() {
        toAll(() -> SseEmitter.event().comment(HEARTBEAT));
    }

    private void broadcastToAll(Broadcast broadcast) {
        toAll(() -> SseEmitter.event().name(BROADCAST).data(broadcast));
    }

    /** {@code event} (a new one per stream: a builder is not reusable) to every stream. */
    private void toAll(Supplier<SseEmitter.SseEventBuilder> event) {
        navsBySession.values().forEach(nav -> send(nav, event));
        anonymous.forEach((emitter, ip) -> {
            if (!send(emitter, event.get())) removeAnonymous(ip, emitter);
        });
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

    /**
     * Whether {@code nav}'s session still holds the login its streams were opened with; if not
     * (logged out, or logged in again, possibly as another account), its streams close. The token
     * is compared rather than its subject: the mock's tokens are not JWTs.
     */
    private static boolean stillLoggedIn(Nav nav) {
        String token = tokenOf(nav.session());
        boolean same = token != null && token.equals(nav.token());
        if (!same) nav.emitters().forEach(SseEmitter::complete);
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
        for (SseEmitter emitter : nav.emitters()) {
            if (!send(emitter, event.get())) remove(nav.sessionId(), emitter);
        }
    }

    /** False when {@code emitter} is gone. */
    private static boolean send(SseEmitter emitter, String name, Object data) {
        return send(emitter, SseEmitter.event().name(name).data(data));
    }

    /** False when {@code emitter} is gone. */
    private static boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
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
        navsBySession.values().forEach(nav -> nav.emitters().forEach(SseEmitter::complete));
        anonymous.keySet().forEach(SseEmitter::complete);
    }
}
