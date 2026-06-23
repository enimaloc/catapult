package fr.enimaloc.catapult.web.ws;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WsSessionRegistryTest {

    private final WsSessionRegistry registry = new WsSessionRegistry();

    private WsSession newSession(String id) {
        WebSocketSession spring = Mockito.mock(WebSocketSession.class);
        Mockito.when(spring.getId()).thenReturn(id);
        return new WsSession(spring);
    }

    @Test
    void add_and_size() {
        registry.add(newSession("s1"));
        registry.add(newSession("s2"));
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void remove_clears_subscriptions() {
        var s = newSession("s1");
        registry.add(s);
        registry.subscribe("s1", "events.global");
        registry.remove("s1");
        assertThat(registry.subscribersOf("events.global")).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void subscribersOf_returns_only_subscribed() {
        var s1 = newSession("s1");
        var s2 = newSession("s2");
        var s3 = newSession("s3");
        registry.add(s1);
        registry.add(s2);
        registry.add(s3);
        registry.subscribe("s1", "events.global");
        registry.subscribe("s3", "events.global");
        assertThat(registry.subscribersOf("events.global"))
                .extracting(WsSession::id).containsExactlyInAnyOrder("s1", "s3");
    }

    @Test
    void no_leak_on_open_close_thousands() {
        for (int i = 0; i < 1000; i++) {
            var s = newSession("s" + i);
            registry.add(s);
            registry.subscribe("s" + i, "notifications.user." + UUID.randomUUID());
            registry.remove("s" + i);
        }
        assertThat(registry.size()).isZero();
    }

    @Test
    void unsubscribe_removes_only_that_channel() {
        var s = newSession("s1");
        registry.add(s);
        registry.subscribe("s1", "events.global");
        registry.subscribe("s1", "events.admin");
        registry.unsubscribe("s1", "events.global");
        assertThat(registry.subscribersOf("events.global")).isEmpty();
        assertThat(registry.subscribersOf("events.admin"))
                .extracting(WsSession::id).containsExactly("s1");
    }
}
