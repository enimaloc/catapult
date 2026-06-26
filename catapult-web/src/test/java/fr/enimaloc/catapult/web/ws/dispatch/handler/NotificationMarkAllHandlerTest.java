package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
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

import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;

class NotificationMarkAllHandlerTest {

    private ApiClient apiClient;
    private NotificationMarkAllHandler handler;

    @BeforeEach
    void setUp() {
        apiClient = mock(ApiClient.class);
        handler = new NotificationMarkAllHandler(apiClient);
    }

    private WsSession authenticatedSession(UUID userId) {
        WsSession session = new WsSession(mock(WebSocketSession.class));
        session.authenticate(userId, Set.of());
        return session;
    }

    @Test
    void markAll_callsApiClient() {
        UUID userId = UUID.randomUUID();
        WsSession s = authenticatedSession(userId);

        handler.handle(s, Map.of());

        verify(apiClient).notificationMarkAll(userId);
    }

    @Test
    void markAll_returnsNull() {
        UUID userId = UUID.randomUUID();
        WsSession s = authenticatedSession(userId);

        Object result = handler.handle(s, null);

        assertThat(result).isNull();
    }

    @Test
    void markAll_requiresAuth() {
        assertThat(handler.requiresAuth()).isTrue();
        assertThat(handler.requiresAdmin()).isFalse();
    }

    @Test
    void markAll_action() {
        assertThat(handler.action()).isEqualTo("notification.markAll");
    }

    @Test
    void markAll_throwsWhenUnauthenticated() {
        WsSession session = new WsSession(mock(WebSocketSession.class));

        assertThatThrownBy(() -> handler.handle(session, null))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("UNAUTHENTICATED"));
    }
}
