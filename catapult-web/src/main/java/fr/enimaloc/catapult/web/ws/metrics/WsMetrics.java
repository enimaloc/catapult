package fr.enimaloc.catapult.web.ws.metrics;

import fr.enimaloc.catapult.web.ws.WsSessionRegistry;
import fr.enimaloc.catapult.web.ws.codec.msg.AuthOkMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.ErrorMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.EventMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.PingMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.ResponseMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.SubDeniedMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.SubOkMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.WsOutgoing;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.util.List;

/**
 * Centralised WebSocket metrics. All instrumentation calls go through this
 * class — never scatter {@code registry.counter(...).increment()} directly.
 *
 * <p>Gauges are registered once in {@link #init()}; counters and timers are
 * resolved lazily via the registry cache (thread-safe, one lookup per distinct
 * tag-set, then cached).</p>
 */
@Component
public class WsMetrics {

    /** Public channel names that get their own subscription gauge. */
    private static final List<String> KNOWN_CHANNEL_CLASSES = List.of(
            "notifications.user", "channel.viewed", "events.global", "events.admin");

    private final MeterRegistry registry;
    private final WsSessionRegistry sessionRegistry;

    public WsMetrics(MeterRegistry registry, WsSessionRegistry sessionRegistry) {
        this.registry = registry;
        this.sessionRegistry = sessionRegistry;
    }

    @PostConstruct
    public void init() {
        Gauge.builder("catapult.ws.sessions.active", sessionRegistry, WsSessionRegistry::size)
                .description("Active WebSocket sessions")
                .register(registry);

        for (String cls : KNOWN_CHANNEL_CLASSES) {
            String prefix = internalPrefix(cls);
            boolean exact = !prefix.endsWith(".");
            Gauge.builder("catapult.ws.subscriptions.active", sessionRegistry,
                    reg -> reg.allSessions().stream()
                            .flatMap(s -> s.subscriptions().stream())
                            .filter(ch -> exact ? ch.equals(prefix) : ch.startsWith(prefix))
                            .count())
                    .tag("channel_class", cls)
                    .description("Active subscriptions by channel class")
                    .register(registry);
        }
    }

    // -------------------------------------------------------------------------
    // Sessions
    // -------------------------------------------------------------------------

    public void recordSessionOpened(boolean authenticated) {
        registry.counter("catapult.ws.sessions.opened",
                "auth", authenticated ? "authenticated" : "anonymous").increment();
    }

    public void recordSessionClosed(CloseStatus status) {
        registry.counter("catapult.ws.sessions.closed",
                "reason", classifyCloseStatus(status)).increment();
    }

    // -------------------------------------------------------------------------
    // Frames
    // -------------------------------------------------------------------------

    public void recordFrameIn(String type) {
        registry.counter("catapult.ws.frames.in", "type", type).increment();
    }

    public void recordFrameOut(String type) {
        registry.counter("catapult.ws.frames.out", "type", type).increment();
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    public void recordActionRequest(String action, boolean ok) {
        registry.counter("catapult.ws.action.requests",
                "action", action, "ok", String.valueOf(ok)).increment();
    }

    public void recordActionDuration(String action, Duration duration) {
        registry.timer("catapult.ws.action.duration", "action", action).record(duration);
    }

    public void recordActionError(String action, String code) {
        registry.counter("catapult.ws.action.errors",
                "action", action, "code", code).increment();
    }

    public void recordRateLimitRejection(String bucket) {
        registry.counter("catapult.ws.rate_limit.rejections", "bucket", bucket).increment();
    }

    // -------------------------------------------------------------------------
    // Redis fanout
    // -------------------------------------------------------------------------

    public void recordRedisEventReceived(String redisChannel) {
        registry.counter("catapult.ws.redis.events_received",
                "channel_class", classify(redisChannel)).increment();
    }

    public void recordFanoutTargets(String redisChannel, int count) {
        registry.counter("catapult.ws.redis.fanout_targets",
                "channel_class", classify(redisChannel)).increment(count);
    }

    // -------------------------------------------------------------------------
    // Auth tickets
    // -------------------------------------------------------------------------

    /**
     * Records an auth-ticket lifecycle event.
     *
     * @param outcome one of {@code issued}, {@code consumed}, {@code replay},
     *                {@code missing}
     */
    public void recordTicket(String outcome) {
        registry.counter("catapult.ws.auth.ticket", "outcome", outcome).increment();
    }

    // -------------------------------------------------------------------------
    // Snapshot (subscription initialiser)
    // -------------------------------------------------------------------------

    public void recordSnapshotDuration(String channel, Duration duration) {
        registry.timer("catapult.ws.snapshot.duration", "channel", channel).record(duration);
    }

    public void recordSnapshotFailure(String channel) {
        registry.counter("catapult.ws.snapshot.failures", "channel", channel).increment();
    }

    // -------------------------------------------------------------------------
    // Static helpers
    // -------------------------------------------------------------------------

    /**
     * Derives a {@code channel_class} label from a Redis pub/sub channel name.
     * Strips the {@code catapult:events:} prefix and takes the first
     * colon-delimited segment (or the whole remainder when there is none).
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code catapult:events:user:abc123} → {@code user}</li>
     *   <li>{@code catapult:events:channel:abc123} → {@code channel}</li>
     *   <li>{@code catapult:events:global} → {@code global}</li>
     * </ul>
     */
    public static String classify(String redisChannel) {
        String after = redisChannel.startsWith("catapult:events:")
                ? redisChannel.substring("catapult:events:".length())
                : redisChannel;
        int colon = after.indexOf(':');
        return colon < 0 ? after : after.substring(0, colon);
    }

    /**
     * Classifies a {@link WsOutgoing} message for the {@code catapult.ws.frames.out}
     * {@code type} tag.
     */
    public static String classifyOutgoing(WsOutgoing msg) {
        return switch (msg) {
            case ResponseMessage ignored  -> "response";
            case EventMessage ignored     -> "event";
            case PingMessage ignored      -> "ping";
            case ErrorMessage ignored     -> "error";
            case AuthOkMessage ignored    -> "auth_ok";
            case SubOkMessage ignored     -> "sub_ok";
            case SubDeniedMessage ignored -> "sub_denied";
        };
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Maps a public channel class to the internal channel prefix used in WsSessionRegistry. */
    private static String internalPrefix(String channelClass) {
        return switch (channelClass) {
            case "notifications.user" -> "notifications.user.";
            case "channel.viewed"     -> "channel.viewed.";
            default                   -> channelClass; // events.global, events.admin → exact
        };
    }

    private static String classifyCloseStatus(CloseStatus status) {
        if (status == null) return "normal";
        return switch (status.getCode()) {
            case 1000 -> "normal";
            case 1001 -> "timeout";
            case 1008 -> "auth_failed";
            case 1011 -> "error";
            default   -> {
                int code = status.getCode();
                yield (code >= 1002 && code <= 1015) || code >= 4000 ? "error" : "normal";
            }
        };
    }
}
