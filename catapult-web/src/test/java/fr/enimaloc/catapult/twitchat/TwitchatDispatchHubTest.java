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

    private final SseStreams sse = SseStreams.direct(new InteractionMetrics(new SimpleMeterRegistry()));
    private final TwitchatDispatchHub hub = new TwitchatDispatchHub(sse);

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
