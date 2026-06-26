package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminNotificationDeleteHandlerTest {

    private ApiClient apiClient;
    private AdminNotificationDeleteHandler handler;

    @BeforeEach
    void setUp() {
        apiClient = mock(ApiClient.class);
        handler = new AdminNotificationDeleteHandler(apiClient);
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

    @Test
    void delete_returnsOkMap() {
        UUID notifId = UUID.randomUUID();
        when(apiClient.adminNotificationDelete(notifId)).thenReturn(true);

        Object result = handler.handle(adminSession(), Map.of("notificationId", notifId.toString()));

        assertThat(result).isEqualTo(Map.of("ok", true));
        verify(apiClient).adminNotificationDelete(notifId);
    }

    @Test
    void delete_nullNotificationId_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of()))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void delete_notFoundFromApi_throwsNotFound() {
        UUID notifId = UUID.randomUUID();
        when(apiClient.adminNotificationDelete(notifId)).thenReturn(false);

        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("notificationId", notifId.toString())))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void delete_action() {
        assertThat(handler.action()).isEqualTo("admin.notification.delete");
    }

    @Test
    void delete_requiresAdmin() {
        assertThat(handler.requiresAuth()).isTrue();
        assertThat(handler.requiresAdmin()).isTrue();
    }

    @Test
    void delete_throwsWhenUnauthenticated() {
        WsSession session = new WsSession(mock(WebSocketSession.class));
        UUID notifId = UUID.randomUUID();

        assertThatThrownBy(() -> handler.handle(session, Map.of("notificationId", notifId.toString())))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void delete_throwsWhenNotAdmin() {
        UUID notifId = UUID.randomUUID();

        assertThatThrownBy(() -> handler.handle(userSession(), Map.of("notificationId", notifId.toString())))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("FORBIDDEN"));
    }
}
