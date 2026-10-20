package fr.enimaloc.catapult.twitchat;

import fr.enimaloc.catapult.controller.SseStreams;
import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TwitchatDispatchHubTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SseStreams sse = SseStreams.direct(new InteractionMetrics(registry));
    private final TwitchatDispatchHub hub = new TwitchatDispatchHub(sse);

    private double widgetsOpen() {
        return registry.get("catapult.web.sse.streams").tag("stream", "twitchat-widget").gauge().value();
    }

    @Test
    void aClosedPage_noLongerCountsForItsOwner_whoIsForgottenWithItsLastOne() {
        UUID owner = UUID.randomUUID();
        SseEmitter first = hub.subscribe(owner, "s1");
        SseEmitter second = hub.subscribe(owner, "s1");

        sse.close(first);
        assertThat(hub.subscriberCount(owner)).isEqualTo(1);
        sse.close(second);
        assertThat(hub.subscriberCount(owner)).isZero();

        hub.publish(owner, "{\"id\":\"d-1\"}");
        assertThat(hub.subscribe(owner, "s1")).isNotNull();
        assertThat(hub.subscriberCount(owner)).isEqualTo(1);
    }

    @Test
    void aSessionLoggingOut_closesItsOwnPages_only() {
        UUID owner = UUID.randomUUID();
        hub.subscribe(owner, "logged-out");
        hub.subscribe(owner, "logged-out");
        hub.subscribe(owner, "still-in");

        sse.closeSession("logged-out");

        assertThat(hub.subscriberCount(owner)).isEqualTo(1);
    }

    @Test
    void aClosedWidget_freesItsAddressesSlot() {
        UUID owner = UUID.randomUUID();
        SseEmitter widget = hub.subscribeWidget(owner, "1.2.3.4", Duration.ofMinutes(10));
        assertThat(widgetsOpen()).isEqualTo(1);

        sse.close(widget);

        assertThat(widgetsOpen()).isZero();
        assertThat(hub.subscriberCount(owner)).isZero();
    }

    @Test
    void publish_reachesOnlyThatUsersStreams() {
        UUID owner = UUID.randomUUID();
        SseEmitter mine = hub.subscribe(owner, "s1");
        SseEmitter other = hub.subscribe(UUID.randomUUID(), "s2");

        hub.publish(owner, "{\"id\":\"d-1\"}");

        assertThat(hub.subscriberCount(owner)).isEqualTo(1);
        assertThat(mine).isNotSameAs(other);
    }

    @Test
    void widgetStreams_closeAfterTheirTimeout_andAreBoundedPerAddress() {
        UUID owner = UUID.randomUUID();

        assertThat(hub.subscribeWidget(owner, "1.2.3.4", Duration.ofMinutes(10)).getTimeout()).isEqualTo(600_000L);
        assertThat(hub.subscribe(owner, "s1").getTimeout()).isEqualTo(SseStreams.TIMEOUT.toMillis());
        assertThat(hub.subscriberCount(owner)).isEqualTo(2);
    }

    @Test
    void publish_withoutSubscribers_isANoOp() {
        hub.publish(UUID.randomUUID(), "{}");
    }
}
