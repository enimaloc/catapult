package fr.enimaloc.catapult.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatapultApiMetricsTest {

    private SimpleMeterRegistry registry;
    private CatapultApiMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CatapultApiMetrics(registry);
    }

    // -------------------------------------------------------------------------
    // Counter: redis events published
    // -------------------------------------------------------------------------

    @Test
    void recordRedisPublished_classifies_user_channel() {
        metrics.recordRedisPublished("catapult:events:user:abc123");
        metrics.recordRedisPublished("catapult:events:user:def456");

        Counter c = registry.get("catapult.redis.events_published")
                .tag("channel_class", "user").counter();
        assertThat(c.count()).isEqualTo(2.0);
    }

    @Test
    void recordRedisPublished_classifies_global_channel() {
        metrics.recordRedisPublished("catapult:events:global");

        Counter c = registry.get("catapult.redis.events_published")
                .tag("channel_class", "global").counter();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void recordRedisPublished_classifies_admin_channel() {
        metrics.recordRedisPublished("catapult:events:admin");

        Counter c = registry.get("catapult.redis.events_published")
                .tag("channel_class", "admin").counter();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void recordRedisPublished_classifies_channel_prefix() {
        metrics.recordRedisPublished("catapult:events:channel:uuid123");

        Counter c = registry.get("catapult.redis.events_published")
                .tag("channel_class", "channel").counter();
        assertThat(c.count()).isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Counter: notification actions
    // -------------------------------------------------------------------------

    @Test
    void recordNotificationAction_created() {
        metrics.recordNotificationAction("created");
        metrics.recordNotificationAction("created");

        Counter c = registry.get("catapult.notification.actions").tag("action", "created").counter();
        assertThat(c.count()).isEqualTo(2.0);
    }

    @Test
    void recordNotificationAction_distinguishes_action_types() {
        metrics.recordNotificationAction("created");
        metrics.recordNotificationAction("read");
        metrics.recordNotificationAction("markAllRead");
        metrics.recordNotificationAction("deleted");

        assertThat(registry.get("catapult.notification.actions").tag("action", "created").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("catapult.notification.actions").tag("action", "read").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("catapult.notification.actions").tag("action", "markAllRead").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("catapult.notification.actions").tag("action", "deleted").counter().count())
                .isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Counter: broadcast attempts
    // -------------------------------------------------------------------------

    @Test
    void recordBroadcastAttempt_accepted_true() {
        metrics.recordBroadcastAttempt("maintenance.scheduled", true);

        Counter c = registry.get("catapult.broadcast.attempts")
                .tag("name", "maintenance.scheduled").tag("accepted", "true").counter();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void recordBroadcastAttempt_accepted_false() {
        metrics.recordBroadcastAttempt("maintenance.scheduled", false);
        metrics.recordBroadcastAttempt("maintenance.scheduled", false);

        Counter c = registry.get("catapult.broadcast.attempts")
                .tag("name", "maintenance.scheduled").tag("accepted", "false").counter();
        assertThat(c.count()).isEqualTo(2.0);
    }

    @Test
    void recordBroadcastAttempt_tracks_different_event_names() {
        metrics.recordBroadcastAttempt("maintenance.scheduled", true);
        metrics.recordBroadcastAttempt("stream.ended", true);

        assertThat(registry.get("catapult.broadcast.attempts")
                .tag("name", "maintenance.scheduled").tag("accepted", "true").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("catapult.broadcast.attempts")
                .tag("name", "stream.ended").tag("accepted", "true").counter().count())
                .isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Static: classify
    // -------------------------------------------------------------------------

    @Test
    void classify_user_channel() {
        assertThat(CatapultApiMetrics.classify("catapult:events:user:abc123")).isEqualTo("user");
    }

    @Test
    void classify_global() {
        assertThat(CatapultApiMetrics.classify("catapult:events:global")).isEqualTo("global");
    }

    @Test
    void classify_channel_prefix() {
        assertThat(CatapultApiMetrics.classify("catapult:events:channel:uuid")).isEqualTo("channel");
    }

    @Test
    void classify_admin() {
        assertThat(CatapultApiMetrics.classify("catapult:events:admin")).isEqualTo("admin");
    }

    @Test
    void classify_unknown_prefix_returned_as_is() {
        assertThat(CatapultApiMetrics.classify("catapult:events:custom")).isEqualTo("custom");
    }
}
