package fr.enimaloc.catapult.web.ws;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.auth.WsTicketStore;
import fr.enimaloc.catapult.web.ws.codec.JsonMessageCodec;
import fr.enimaloc.catapult.web.ws.dispatch.ChannelResolver;
import fr.enimaloc.catapult.web.ws.dispatch.HtmxWsDispatcher;
import fr.enimaloc.catapult.web.ws.dispatch.SubscriptionInitializer;
import fr.enimaloc.catapult.web.ws.dispatch.WsRequestDispatcher;
import fr.enimaloc.catapult.web.ws.ratelimit.WsRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link WsHub} calls matching {@link SubscriptionInitializer}s
 * after sending {@code sub.ok}, and skips them for unrelated channels.
 */
class WsHubSubscriptionInitializerTest {

    private WsSessionRegistry registry;
    private ChannelResolver channelResolver;
    private JsonMessageCodec codec;

    @BeforeEach
    void setUp() {
        registry = new WsSessionRegistry();
        channelResolver = new ChannelResolver(mock(ApiClient.class));
        codec = new JsonMessageCodec();
    }

    private WsHub buildHub(List<SubscriptionInitializer> initializers) {
        WsRateLimiter rateLimiter = new WsRateLimiter();
        WsRequestDispatcher dispatcher = new WsRequestDispatcher(List.of(), rateLimiter);
        return new WsHub(registry, channelResolver, codec, mock(WsTicketStore.class),
                dispatcher, rateLimiter, mock(HtmxWsDispatcher.class), initializers);
    }

    /**
     * Creates a real {@link WsSession} backed by a mock {@link WebSocketSession},
     * adds it to the registry, and authenticates it.
     * Returns [wsSession, springSession] so callers can verify calls on the spring mock.
     */
    private Object[] authenticatedSession(UUID userId) {
        WebSocketSession springSession = mock(WebSocketSession.class);
        when(springSession.getId()).thenReturn("ws-" + userId);
        when(springSession.isOpen()).thenReturn(true);
        WsSession session = new WsSession(springSession);
        session.authenticate(userId, Set.of("ROLE_USER"), "test-jwt");
        registry.add(session);
        return new Object[]{session, springSession};
    }

    @Test
    void onSubscribe_callsMatchingInitializer() throws Exception {
        SubscriptionInitializer init = mock(SubscriptionInitializer.class);
        when(init.publicChannel()).thenReturn("notifications.user");

        UUID userId = UUID.randomUUID();
        Object[] pair = authenticatedSession(userId);
        WsSession session = (WsSession) pair[0];
        WebSocketSession springSession = (WebSocketSession) pair[1];

        List<String> sent = new ArrayList<>();
        doAnswer(inv -> {
            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(springSession).sendMessage(any());

        WsHub hub = buildHub(List.of(init));
        hub.handleSubscribe(session, "notifications.user");

        assertThat(sent).anyMatch(f -> f.contains("sub.ok"));

        InOrder order = inOrder(springSession, init);
        order.verify(springSession).sendMessage(
                argThat(m -> ((TextMessage) m).getPayload().contains("sub.ok")));
        order.verify(init).onSubscribe(session);
    }

    @Test
    void onSubscribe_skipsInitializerForOtherChannel() throws Exception {
        SubscriptionInitializer init = mock(SubscriptionInitializer.class);
        when(init.publicChannel()).thenReturn("notifications.user");

        UUID userId = UUID.randomUUID();
        Object[] pair = authenticatedSession(userId);
        WsSession session = (WsSession) pair[0];
        WebSocketSession springSession = (WebSocketSession) pair[1];

        doAnswer(inv -> null).when(springSession).sendMessage(any());

        WsHub hub = buildHub(List.of(init));
        hub.handleSubscribe(session, "events.global");

        verify(init, never()).onSubscribe(any());
    }

    @Test
    void onSubscribe_noInitializersRegistered_stillSendsSubOk() throws Exception {
        UUID userId = UUID.randomUUID();
        Object[] pair = authenticatedSession(userId);
        WsSession session = (WsSession) pair[0];
        WebSocketSession springSession = (WebSocketSession) pair[1];

        List<String> sent = new ArrayList<>();
        doAnswer(inv -> {
            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(springSession).sendMessage(any());

        WsHub hub = buildHub(List.of());
        hub.handleSubscribe(session, "events.global");

        assertThat(sent).anyMatch(f -> f.contains("sub.ok"));
    }
}
