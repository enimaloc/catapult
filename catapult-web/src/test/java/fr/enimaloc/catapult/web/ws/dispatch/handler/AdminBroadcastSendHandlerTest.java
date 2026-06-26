package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBroadcastSendHandlerTest {

    private ApiClient apiClient;
    private AdminBroadcastSendHandler handler;

    @BeforeEach
    void setUp() {
        apiClient = mock(ApiClient.class);
        handler = new AdminBroadcastSendHandler(apiClient);
    }

    private WsSession adminSession() {
        WsSession session = new WsSession(mock(WebSocketSession.class));
        session.authenticate(UUID.randomUUID(), Set.of("ROLE_ADMIN"));
        return session;
    }

    private WsSession userSession() {
        WsSession session = new WsSession(mock(WebSocketSession.class));
        session.authenticate(UUID.randomUUID(), Set.of());
        return session;
    }

    private Map<String, Object> validParams() {
        return Map.of(
                "channel", "events.global",
                "name", "alert.info",
                "data", Map.of("title", "Hi", "body", "msg", "ttlSeconds", 60)
        );
    }

    @Test
    void send_callsApiAndReturnsAccepted() {
        when(apiClient.adminBroadcastSend(any())).thenReturn(Map.of("accepted", true));

        Object result = handler.handle(adminSession(), validParams());

        assertThat(result).isEqualTo(Map.of("accepted", true));
        verify(apiClient).adminBroadcastSend(any());
    }

    @Test
    void send_eventsAdminChannelAccepted() {
        when(apiClient.adminBroadcastSend(any())).thenReturn(Map.of("accepted", true));

        Object result = handler.handle(adminSession(), Map.of(
                "channel", "events.admin",
                "name", "alert.warning",
                "data", Map.of("title", "X", "body", "Y")));

        assertThat(result).isEqualTo(Map.of("accepted", true));
    }

    @Test
    void send_invalidChannel_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of(
                "channel", "events.unknown",
                "name", "alert.info",
                "data", Map.of())))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void send_nullChannel_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of(
                "name", "alert.info")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void send_blankName_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of(
                "channel", "events.global",
                "name", "   ")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void send_nullName_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of(
                "channel", "events.global")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void send_nullData_passesEmptyMap() {
        when(apiClient.adminBroadcastSend(any())).thenReturn(Map.of("accepted", true));

        handler.handle(adminSession(), Map.of(
                "channel", "events.global",
                "name", "version.deployed"));

        verify(apiClient).adminBroadcastSend(
                new AdminBroadcastSendHandler.Params("events.global", "version.deployed", Collections.emptyMap()));
    }

    @Test
    void send_apiReturnsNull_throwsInternal() {
        when(apiClient.adminBroadcastSend(any())).thenReturn(null);

        assertThatThrownBy(() -> handler.handle(adminSession(), validParams()))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("INTERNAL"));
    }

    @Test
    void send_action() {
        assertThat(handler.action()).isEqualTo("admin.broadcast.send");
    }

    @Test
    void send_requiresAuth() {
        assertThat(handler.requiresAuth()).isTrue();
    }

    @Test
    void send_requiresAdmin() {
        assertThat(handler.requiresAdmin()).isTrue();
    }

    @Test
    void send_throwsWhenUnauthenticated() {
        WsSession session = new WsSession(mock(WebSocketSession.class));

        assertThatThrownBy(() -> handler.handle(session, validParams()))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void send_throwsWhenNotAdmin() {
        assertThatThrownBy(() -> handler.handle(userSession(), validParams()))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("FORBIDDEN"));
    }
}
