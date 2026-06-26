package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;

class NotificationListHandlerTest {

    private ApiClient apiClient;
    private NotificationListHandler handler;

    @BeforeEach
    void setUp() {
        apiClient = mock(ApiClient.class);
        handler = new NotificationListHandler(apiClient);
    }

    private WsSession authenticatedSession(UUID userId) {
        WsSession session = new WsSession(mock(WebSocketSession.class));
        session.authenticate(userId, Set.of());
        return session;
    }

    @Test
    void list_returnsPageFromApi() throws Exception {
        UUID userId = UUID.randomUUID();
        Map<String, Object> page = Map.of("content", List.of(), "totalElements", 0, "totalPages", 1);
        when(apiClient.notificationList(userId, 0, 10)).thenReturn(page);
        WsSession s = authenticatedSession(userId);

        Object result = handler.handle(s, Map.of("page", 0, "size", 10));

        assertThat(result).isEqualTo(page);
    }

    @Test
    void list_capsSizeAt50() throws Exception {
        UUID userId = UUID.randomUUID();
        WsSession s = authenticatedSession(userId);

        handler.handle(s, Map.of("page", 0, "size", 500));

        verify(apiClient).notificationList(userId, 0, 50);
    }

    @Test
    void list_defaultsToPage0Size10WhenParamsAbsent() throws Exception {
        UUID userId = UUID.randomUUID();
        WsSession s = authenticatedSession(userId);

        handler.handle(s, Map.of());

        verify(apiClient).notificationList(userId, 0, 10);
    }

    @Test
    void list_clampsNegativePage() throws Exception {
        UUID userId = UUID.randomUUID();
        WsSession s = authenticatedSession(userId);

        handler.handle(s, Map.of("page", -5, "size", 10));

        verify(apiClient).notificationList(userId, 0, 10);
    }

    @Test
    void list_requiresAuth() {
        assertThat(handler.requiresAuth()).isTrue();
        assertThat(handler.requiresAdmin()).isFalse();
    }

    @Test
    void list_action() {
        assertThat(handler.action()).isEqualTo("notification.list");
    }

    @Test
    void list_throwsWhenUnauthenticated() {
        WsSession session = new WsSession(mock(WebSocketSession.class));

        assertThatThrownBy(() -> handler.handle(session, Map.of()))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("UNAUTHENTICATED"));
    }
}
