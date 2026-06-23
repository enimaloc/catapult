package fr.enimaloc.catapult.web.ws.lifecycle;

import fr.enimaloc.catapult.web.ws.WsHub;
import fr.enimaloc.catapult.web.ws.codec.msg.EventMessage;
import fr.enimaloc.catapult.web.ws.dispatch.ChannelResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pushes a {@code maintenance.imminent} event to every connected WebSocket
 * client when the Spring context closes (SIGTERM, manual shutdown, devtools
 * restart…) so browsers can flash the « shutdown » overlay immediately
 * instead of waiting for the 45 s heartbeat watchdog to fire.
 *
 * <p>The event is broadcast <strong>directly through the local hub</strong>,
 * not via Redis pub/sub: by the time we receive {@link ContextClosedEvent},
 * the {@code RedisMessageListenerContainer} may already be stopping its
 * worker thread, and even if it isn't, the round-trip Web → Redis → Web
 * would burn time we don't have between SIGTERM and SIGKILL under Docker.
 * Direct fanout is one method call per session — fast and reliable.</p>
 *
 * <p>After the broadcast we sleep ~500 ms to let Tomcat actually flush the
 * outgoing frames before the connector closes. 500 ms is empirical: long
 * enough for the WS frames to reach the wire on a healthy local machine,
 * short enough to stay well under the typical 10 s grace period Docker
 * gives a stopping container.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GracefulShutdownBroadcaster {

    /** Public WS channel the client subscribes to to receive global events. */
    public static final String PUBLIC_CHANNEL = "events.global";

    /** Reserved name — see {@code BroadcastValidator} in catapult-api. */
    public static final String EVENT_NAME = "maintenance.imminent";

    /** Empirical wait so outgoing WS frames flush before the connector closes. */
    static final long FLUSH_WAIT_MS = 500L;

    /** Default ETA we advertise to the client (matches typical SIGTERM grace). */
    static final int DEFAULT_ETA_SECONDS = 10;

    private final WsHub hub;
    private final ChannelResolver channelResolver;
    private final java.util.concurrent.atomic.AtomicBoolean alreadyBroadcast =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        // Spring Boot fires ContextClosedEvent on both the main application
        // context and the management (actuator) child context, which made the
        // browser see the imminent frame twice. Skip the child context and
        // also guard with a single-fire flag for any other re-entrancy path.
        if (event.getApplicationContext().getParent() != null) {
            return;
        }
        if (!alreadyBroadcast.compareAndSet(false, true)) {
            return;
        }
        broadcastImminent();
        sleepForFrameFlush();
    }

    private void broadcastImminent() {
        Map<String, Object> data = new LinkedHashMap<>(2);
        data.put("reason", "shutdown");
        data.put("etaSeconds", DEFAULT_ETA_SECONDS);

        // The hub's broadcast() takes the *internal* channel key
        // (resolveRedisToInternal maps catapult:events:global → events.global,
        // which happens to be the same string for the global channel).
        String internalChannel = channelResolver.resolveRedisToInternal("catapult:events:global");
        if (internalChannel == null) {
            internalChannel = PUBLIC_CHANNEL;
        }
        EventMessage event = new EventMessage(PUBLIC_CHANNEL, EVENT_NAME, data);
        try {
            hub.broadcast(internalChannel, event);
            log.info("shutdown broadcast: {} on {}", EVENT_NAME, PUBLIC_CHANNEL);
        } catch (Exception ex) {
            // Never let a broken socket abort the shutdown sequence.
            log.warn("shutdown broadcast failed: {}", ex.toString());
        }
    }

    private void sleepForFrameFlush() {
        try {
            Thread.sleep(FLUSH_WAIT_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
