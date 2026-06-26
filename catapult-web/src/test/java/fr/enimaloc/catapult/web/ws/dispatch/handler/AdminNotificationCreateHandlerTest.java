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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminNotificationCreateHandlerTest {

    private ApiClient apiClient;
    private AdminNotificationCreateHandler handler;

    @BeforeEach
    void setUp() {
        apiClient = mock(ApiClient.class);
        handler = new AdminNotificationCreateHandler(apiClient);
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
        return Map.of("title", "Hello", "body", "World content", "severity", "INFO");
    }

    @Test
    void create_callsApiClientAndReturnsResult() {
        Map<String, Object> created = Map.of("id", UUID.randomUUID().toString(), "title", "Hello");
        when(apiClient.adminNotificationCreate(any())).thenReturn(created);

        Object result = handler.handle(adminSession(), validParams());

        assertThat(result).isEqualTo(created);
        verify(apiClient).adminNotificationCreate(any());
    }

    @Test
    void create_blankTitle_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(),
                Map.of("title", "", "body", "x", "severity", "INFO")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void create_nullTitle_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(),
                Map.of("body", "x", "severity", "INFO")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void create_titleTooLong_throwsValidation() {
        String longTitle = "a".repeat(201);
        assertThatThrownBy(() -> handler.handle(adminSession(),
                Map.of("title", longTitle, "body", "x", "severity", "INFO")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void create_blankBody_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(),
                Map.of("title", "T", "body", "  ", "severity", "INFO")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void create_bodyTooLong_throwsValidation() {
        String longBody = "a".repeat(4001);
        assertThatThrownBy(() -> handler.handle(adminSession(),
                Map.of("title", "T", "body", longBody, "severity", "INFO")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void create_nullSeverity_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(),
                Map.of("title", "T", "body", "x")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void create_invalidSeverity_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(),
                Map.of("title", "T", "body", "x", "severity", "CRITICAL")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void create_ctaUrlTooLong_throwsValidation() {
        String longUrl = "https://example.com/" + "a".repeat(490);
        assertThatThrownBy(() -> handler.handle(adminSession(),
                Map.of("title", "T", "body", "x", "severity", "INFO", "ctaUrl", longUrl)))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void create_ctaLabelTooLong_throwsValidation() {
        String longLabel = "a".repeat(81);
        assertThatThrownBy(() -> handler.handle(adminSession(),
                Map.of("title", "T", "body", "x", "severity", "INFO", "ctaLabel", longLabel)))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void create_warningAndErrorSeverityAccepted() {
        Map<String, Object> created = Map.of("id", UUID.randomUUID().toString());
        when(apiClient.adminNotificationCreate(any())).thenReturn(created);

        handler.handle(adminSession(), Map.of("title", "T", "body", "x", "severity", "WARNING"));
        handler.handle(adminSession(), Map.of("title", "T", "body", "x", "severity", "ERROR"));
        handler.handle(adminSession(), Map.of("title", "T", "body", "x", "severity", "WARN"));
    }

    @Test
    void create_nullTargetUserId_broadcastAllowed() {
        Map<String, Object> created = Map.of("id", UUID.randomUUID().toString());
        when(apiClient.adminNotificationCreate(any())).thenReturn(created);

        Object result = handler.handle(adminSession(), validParams());

        assertThat(result).isEqualTo(created);
    }

    @Test
    void create_action() {
        assertThat(handler.action()).isEqualTo("admin.notification.create");
    }

    @Test
    void create_requiresAdmin() {
        assertThat(handler.requiresAuth()).isTrue();
        assertThat(handler.requiresAdmin()).isTrue();
    }

    @Test
    void create_throwsWhenUnauthenticated() {
        WsSession session = new WsSession(mock(WebSocketSession.class));

        assertThatThrownBy(() -> handler.handle(session, validParams()))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void create_throwsWhenNotAdmin() {
        assertThatThrownBy(() -> handler.handle(userSession(), validParams()))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void create_apiReturnsNull_throwsInternal() {
        when(apiClient.adminNotificationCreate(any())).thenReturn(null);

        assertThatThrownBy(() -> handler.handle(adminSession(), validParams()))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("INTERNAL"));
    }
}
