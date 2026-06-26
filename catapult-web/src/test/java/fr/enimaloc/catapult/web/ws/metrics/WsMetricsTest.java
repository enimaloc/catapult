package fr.enimaloc.catapult.web.ws.metrics;

import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.WsSessionRegistry;
import fr.enimaloc.catapult.web.ws.codec.msg.AuthOkMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.ErrorMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.EventMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.PingMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.ResponseMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.SubDeniedMessage;
import fr.enimaloc.catapult.web.ws.codec.msg.SubOkMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WsMetricsTest {

    private SimpleMeterRegistry registry;
    private WsSessionRegistry sessionRegistry;
    private WsMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        sessionRegistry = new WsSessionRegistry();
        metrics = new WsMetrics(registry, sessionRegistry);
        metrics.init();
    }

    // -------------------------------------------------------------------------
    // Gauge: sessions.active
    // -------------------------------------------------------------------------

    @Test
    void sessionsActive_gauge_reflects_registry_size() {
        assertThat(registry.get("catapult.ws.sessions.active").gauge().value()).isEqualTo(0.0);

        WebSocketSession spring = mock(WebSocketSession.class);
        when(spring.getId()).thenReturn("s1");
        sessionRegistry.add(new WsSession(spring));

        assertThat(registry.get("catapult.ws.sessions.active").gauge().value()).isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Counter: sessions.opened
    // -------------------------------------------------------------------------

    @Test
    void recordSessionOpened_anonymous_increments_counter() {
        metrics.recordSessionOpened(false);
        metrics.recordSessionOpened(false);

        Counter c = registry.get("catapult.ws.sessions.opened").tag("auth", "anonymous").counter();
        assertThat(c.count()).isEqualTo(2.0);
    }

    @Test
    void recordSessionOpened_authenticated_increments_counter() {
        metrics.recordSessionOpened(true);

        Counter c = registry.get("catapult.ws.sessions.opened").tag("auth", "authenticated").counter();
        assertThat(c.count()).isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Counter: sessions.closed
    // -------------------------------------------------------------------------

    @Test
    void recordSessionClosed_normal_status() {
        metrics.recordSessionClosed(CloseStatus.NORMAL);

        Counter c = registry.get("catapult.ws.sessions.closed").tag("reason", "normal").counter();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void recordSessionClosed_going_away_is_timeout() {
        metrics.recordSessionClosed(CloseStatus.GOING_AWAY);

        Counter c = registry.get("catapult.ws.sessions.closed").tag("reason", "timeout").counter();
        assertThat(c.count()).isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Counter: frames.in
    // -------------------------------------------------------------------------

    @Test
    void recordFrameIn_increments_by_type() {
        metrics.recordFrameIn("request");
        metrics.recordFrameIn("request");
        metrics.recordFrameIn("subscribe");

        assertThat(registry.get("catapult.ws.frames.in").tag("type", "request").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("catapult.ws.frames.in").tag("type", "subscribe").counter().count())
                .isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Counter: frames.out — classifyOutgoing
    // -------------------------------------------------------------------------

    @Test
    void classifyOutgoing_response() {
        assertThat(WsMetrics.classifyOutgoing(ResponseMessage.ok("id-1", null))).isEqualTo("response");
    }

    @Test
    void classifyOutgoing_event() {
        assertThat(WsMetrics.classifyOutgoing(new EventMessage("events.global", "test", null)))
                .isEqualTo("event");
    }

    @Test
    void classifyOutgoing_ping() {
        assertThat(WsMetrics.classifyOutgoing(new PingMessage(System.currentTimeMillis())))
                .isEqualTo("ping");
    }

    @Test
    void classifyOutgoing_error() {
        assertThat(WsMetrics.classifyOutgoing(new ErrorMessage("ERR", "msg"))).isEqualTo("error");
    }

    @Test
    void classifyOutgoing_auth_ok() {
        assertThat(WsMetrics.classifyOutgoing(new AuthOkMessage(UUID.randomUUID(), List.of(), "csrf")))
                .isEqualTo("auth_ok");
    }

    @Test
    void classifyOutgoing_sub_ok() {
        assertThat(WsMetrics.classifyOutgoing(new SubOkMessage("events.global"))).isEqualTo("sub_ok");
    }

    @Test
    void classifyOutgoing_sub_denied() {
        assertThat(WsMetrics.classifyOutgoing(new SubDeniedMessage("events.admin", "FORBIDDEN")))
                .isEqualTo("sub_denied");
    }

    @Test
    void recordFrameOut_increments_counter() {
        metrics.recordFrameOut("response");
        metrics.recordFrameOut("response");
        metrics.recordFrameOut("event");

        assertThat(registry.get("catapult.ws.frames.out").tag("type", "response").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("catapult.ws.frames.out").tag("type", "event").counter().count())
                .isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Counter + Timer: action metrics
    // -------------------------------------------------------------------------

    @Test
    void recordActionRequest_ok_increments_counter_with_tags() {
        metrics.recordActionRequest("game.search", true);
        metrics.recordActionRequest("game.search", true);
        metrics.recordActionRequest("game.search", false);

        assertThat(registry.get("catapult.ws.action.requests")
                .tag("action", "game.search").tag("ok", "true").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("catapult.ws.action.requests")
                .tag("action", "game.search").tag("ok", "false").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordActionDuration_records_timer() {
        metrics.recordActionDuration("game.search", Duration.ofMillis(42));

        Timer t = registry.get("catapult.ws.action.duration").tag("action", "game.search").timer();
        assertThat(t.count()).isEqualTo(1L);
        assertThat(t.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(42.0);
    }

    @Test
    void recordActionError_increments_with_action_and_code_tags() {
        metrics.recordActionError("game.search", "NOT_FOUND");
        metrics.recordActionError("game.search", "NOT_FOUND");
        metrics.recordActionError("game.search", "FORBIDDEN");

        assertThat(registry.get("catapult.ws.action.errors")
                .tag("action", "game.search").tag("code", "NOT_FOUND").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("catapult.ws.action.errors")
                .tag("action", "game.search").tag("code", "FORBIDDEN").counter().count()).isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Counter: rate_limit.rejections
    // -------------------------------------------------------------------------

    @Test
    void recordRateLimitRejection_increments_by_bucket() {
        metrics.recordRateLimitRejection("global");
        metrics.recordRateLimitRejection("search");

        assertThat(registry.get("catapult.ws.rate_limit.rejections").tag("bucket", "global").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("catapult.ws.rate_limit.rejections").tag("bucket", "search").counter().count())
                .isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Gauge: subscriptions.active
    // -------------------------------------------------------------------------

    @Test
    void subscriptionsActive_gauge_counts_internal_subscriptions() {
        WebSocketSession spring = mock(WebSocketSession.class);
        when(spring.getId()).thenReturn("s1");
        WsSession ws = new WsSession(spring);
        sessionRegistry.add(ws);
        sessionRegistry.subscribe("s1", "notifications.user." + UUID.randomUUID());
        sessionRegistry.subscribe("s1", "events.global");

        assertThat(registry.get("catapult.ws.subscriptions.active")
                .tag("channel_class", "notifications.user").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("catapult.ws.subscriptions.active")
                .tag("channel_class", "events.global").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("catapult.ws.subscriptions.active")
                .tag("channel_class", "events.admin").gauge().value()).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // Counter: redis events
    // -------------------------------------------------------------------------

    @Test
    void recordRedisEventReceived_classifies_channel() {
        metrics.recordRedisEventReceived("catapult:events:user:abc123");
        metrics.recordRedisEventReceived("catapult:events:global");

        assertThat(registry.get("catapult.ws.redis.events_received")
                .tag("channel_class", "user").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("catapult.ws.redis.events_received")
                .tag("channel_class", "global").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordFanoutTargets_increments_by_count() {
        metrics.recordFanoutTargets("catapult:events:global", 5);

        assertThat(registry.get("catapult.ws.redis.fanout_targets")
                .tag("channel_class", "global").counter().count()).isEqualTo(5.0);
    }

    // -------------------------------------------------------------------------
    // Counter: auth tickets
    // -------------------------------------------------------------------------

    @Test
    void recordTicket_increments_by_outcome() {
        metrics.recordTicket("issued");
        metrics.recordTicket("issued");
        metrics.recordTicket("consumed");
        metrics.recordTicket("replay");

        assertThat(registry.get("catapult.ws.auth.ticket").tag("outcome", "issued").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("catapult.ws.auth.ticket").tag("outcome", "consumed").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("catapult.ws.auth.ticket").tag("outcome", "replay").counter().count())
                .isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Timer + Counter: snapshot
    // -------------------------------------------------------------------------

    @Test
    void recordSnapshotDuration_records_timer_by_channel() {
        metrics.recordSnapshotDuration("notifications.user", Duration.ofMillis(10));

        Timer t = registry.get("catapult.ws.snapshot.duration")
                .tag("channel", "notifications.user").timer();
        assertThat(t.count()).isEqualTo(1L);
    }

    @Test
    void recordSnapshotFailure_increments_counter_by_channel() {
        metrics.recordSnapshotFailure("notifications.user");

        assertThat(registry.get("catapult.ws.snapshot.failures")
                .tag("channel", "notifications.user").counter().count()).isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Static: classify
    // -------------------------------------------------------------------------

    @Test
    void classify_user_channel() {
        assertThat(WsMetrics.classify("catapult:events:user:abc123")).isEqualTo("user");
    }

    @Test
    void classify_channel_prefix() {
        assertThat(WsMetrics.classify("catapult:events:channel:uuid")).isEqualTo("channel");
    }

    @Test
    void classify_global() {
        assertThat(WsMetrics.classify("catapult:events:global")).isEqualTo("global");
    }
}
