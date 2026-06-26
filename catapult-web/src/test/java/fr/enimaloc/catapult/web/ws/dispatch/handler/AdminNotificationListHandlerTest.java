package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
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

class AdminNotificationListHandlerTest {

    private ApiClient apiClient;
    private AdminNotificationListHandler handler;

    @BeforeEach
    void setUp() {
        apiClient = mock(ApiClient.class);
        handler = new AdminNotificationListHandler(apiClient);
    }

    private WsSession adminSession() {
        UUID userId = UUID.randomUUID();
        WsSession session = new WsSession(mock(WebSocketSession.class));
        session.authenticate(userId, Set.of("ROLE_ADMIN"));
        return session;
    }

    private WsSession userSession() {
        WsSession session = new WsSession(mock(WebSocketSession.class));
        session.authenticate(UUID.randomUUID(), Set.of());
        return session;
    }

    @Test
    void list_returnsPageFromApi() {
        Map<String, Object> page = Map.of("content", List.of(), "totalElements", 0, "totalPages", 1);
        when(apiClient.adminNotificationList(0, 20)).thenReturn(page);

        Object result = handler.handle(adminSession(), Map.of("page", 0, "size", 20));

        assertThat(result).isEqualTo(page);
    }

    @Test
    void list_capsSizeAt50() {
        handler.handle(adminSession(), Map.of("page", 0, "size", 500));

        verify(apiClient).adminNotificationList(0, 50);
    }

    @Test
    void list_defaultsToPage0Size20WhenParamsAbsent() {
        handler.handle(adminSession(), Map.of());

        verify(apiClient).adminNotificationList(0, 20);
    }

    @Test
    void list_clampsNegativePage() {
        handler.handle(adminSession(), Map.of("page", -5, "size", 10));

        verify(apiClient).adminNotificationList(0, 10);
    }

    @Test
    void list_action() {
        assertThat(handler.action()).isEqualTo("admin.notification.list");
    }

    @Test
    void list_requiresAdmin() {
        assertThat(handler.requiresAuth()).isTrue();
        assertThat(handler.requiresAdmin()).isTrue();
    }

    @Test
    void list_throwsWhenUnauthenticated() {
        WsSession session = new WsSession(mock(WebSocketSession.class));

        assertThatThrownBy(() -> handler.handle(session, Map.of()))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void list_throwsWhenNotAdmin() {
        assertThatThrownBy(() -> handler.handle(userSession(), Map.of()))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void list_apiReturnsNull_throwsInternal() {
        when(apiClient.adminNotificationList(0, 20)).thenReturn(null);

        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of()))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("INTERNAL"));
    }
}
