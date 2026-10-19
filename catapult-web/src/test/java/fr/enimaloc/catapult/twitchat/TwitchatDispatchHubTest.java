package fr.enimaloc.catapult.twitchat;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TwitchatDispatchHubTest {

    @Test
    void publish_reachesOnlyThatUsersEmitters() throws IOException {
        TwitchatDispatchHub hub = new TwitchatDispatchHub();
        UUID owner = UUID.randomUUID();
        SseEmitter mine = mock(SseEmitter.class);
        SseEmitter other = mock(SseEmitter.class);
        hub.subscribe(owner, mine);
        hub.subscribe(UUID.randomUUID(), other);

        hub.publish(owner, "{\"id\":\"d-1\"}");

        verify(mine).send(any(SseEmitter.SseEventBuilder.class));
        verify(other, never()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(hub.subscriberCount(owner)).isEqualTo(1);
    }

    @Test
    void publish_dropsAnEmitterWhoseSendFails() throws IOException {
        TwitchatDispatchHub hub = new TwitchatDispatchHub();
        UUID owner = UUID.randomUUID();
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IOException("gone")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        hub.subscribe(owner, broken);

        hub.publish(owner, "{}");

        assertThat(hub.subscriberCount(owner)).isZero();
    }

    @Test
    void subscribe_withATimeout_closesTheStreamAfterIt_andTheOpenEndedOneNever() {
        TwitchatDispatchHub hub = new TwitchatDispatchHub();
        UUID owner = UUID.randomUUID();

        assertThat(hub.subscribe(owner, Duration.ofMinutes(10)).getTimeout()).isEqualTo(600_000L);
        assertThat(hub.subscribe(owner).getTimeout()).isZero();
        assertThat(hub.subscriberCount(owner)).isEqualTo(2);
    }

    @Test
    void publish_withoutSubscribers_isANoOp() {
        new TwitchatDispatchHub().publish(UUID.randomUUID(), "{}");
    }
}
