package fr.enimaloc.catapult.web.ws;

import fr.enimaloc.catapult.web.ws.auth.WsTicketStore;
import fr.enimaloc.catapult.web.ws.codec.JsonMessageCodec;
import fr.enimaloc.catapult.web.ws.codec.msg.AuthMessage;
import fr.enimaloc.catapult.web.ws.dispatch.ChannelResolver;
import fr.enimaloc.catapult.web.ws.dispatch.HtmxWsDispatcher;
import fr.enimaloc.catapult.web.ws.dispatch.WsRequestDispatcher;
import fr.enimaloc.catapult.web.ws.ratelimit.WsRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the auth-frame branch of {@link WsHub#handleTextMessage}.
 * Uses a real {@link JsonMessageCodec} and {@link WsSessionRegistry}; mocks the
 * Spring {@link WebSocketSession} and {@link WsTicketStore} to isolate behaviour.
 */
class WsHubAuthTest {

    private WsSessionRegistry registry;
    private ChannelResolver channelResolver;
    private JsonMessageCodec codec;
    private WsTicketStore ticketStore;
    private WsHub hub;

    private WebSocketSession springSession;
    private WsSession session;

    @BeforeEach
    void setUp() throws Exception {
        registry = new WsSessionRegistry();
        channelResolver = new ChannelResolver(mock(fr.enimaloc.catapult.client.ApiClient.class));
        codec = new JsonMessageCodec();
        ticketStore = mock(WsTicketStore.class);
        WsRateLimiter rateLimiter = new WsRateLimiter();
        WsRequestDispatcher dispatcher = new WsRequestDispatcher(java.util.List.of(), rateLimiter);
        HtmxWsDispatcher htmxDispatcher = mock(HtmxWsDispatcher.class);
        hub = new WsHub(registry, channelResolver, codec, ticketStore, dispatcher, rateLimiter, htmxDispatcher, java.util.List.of());

        springSession = mock(WebSocketSession.class);
        when(springSession.getId()).thenReturn("ws-1");
        when(springSession.isOpen()).thenReturn(true);
        hub.afterConnectionEstablished(springSession);
        session = registry.get("ws-1");
    }

    @Test
    void valid_ticket_authenticates_session_and_emits_auth_ok() throws Exception {
        UUID userId = UUID.randomUUID();
        when(ticketStore.consume("good"))
                .thenReturn(Optional.of(new WsTicketStore.AuthSnapshot(userId, Set.of("ROLE_USER"))));

        var sent = new java.util.ArrayList<String>();
        doAnswer(inv -> {
            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(springSession).sendMessage(any());

        hub.handleTextMessage(springSession, new TextMessage(codec.encode(new AuthMessage("good"))));

        assertThat(session.userId()).contains(userId);
        assertThat(session.roles()).contains("ROLE_USER");
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).contains("\"type\":\"auth.ok\"").contains(userId.toString());
        verify(springSession, never()).close(any(CloseStatus.class));
    }

    @Test
    void invalid_ticket_emits_error_and_closes_session() throws Exception {
        when(ticketStore.consume("bad")).thenReturn(Optional.empty());

        var sent = new java.util.ArrayList<String>();
        doAnswer(inv -> {
            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(springSession).sendMessage(any());

        hub.handleTextMessage(springSession, new TextMessage(codec.encode(new AuthMessage("bad"))));

        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).contains("\"type\":\"error\"").contains("INVALID_TICKET");
        assertThat(session.userId()).isEmpty();
        verify(springSession, times(1)).close(CloseStatus.NORMAL);
    }
}
