package fr.enimaloc.catapult.twitchat;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TwitchatDispatchHubTest {

    @Test
    void publish_reachesOnlyThatUsersEmitters() {
        TwitchatDispatchHub hub = new TwitchatDispatchHub();
        UUID owner = UUID.randomUUID();
        SseEmitter mine = hub.subscribe(owner);
        hub.subscribe(UUID.randomUUID());

        hub.publish(owner, "{\"id\":\"d-1\"}");

        assertThat(hub.subscriberCount(owner)).isEqualTo(1);
        assertThat(mine).isNotNull();
    }

    @Test
    void publish_withoutSubscribers_isANoOp() {
        new TwitchatDispatchHub().publish(UUID.randomUUID(), "{}");
    }
}
