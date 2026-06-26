package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.service.config.WebConfigOverrideService;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminConfigResetHandlerTest {

    private ApiClient apiClient;
    private WebConfigOverrideService webConfigOverrideService;
    private AdminConfigResetHandler handler;

    @BeforeEach
    void setUp() {
        apiClient = mock(ApiClient.class);
        webConfigOverrideService = mock(WebConfigOverrideService.class);
        handler = new AdminConfigResetHandler(apiClient, webConfigOverrideService);
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
    void reset_apiModule_callsApiClientAndReturnsOk() {
        when(apiClient.adminConfigReset("ping-interval")).thenReturn(true);

        Object result = handler.handle(adminSession(), Map.of("module", "api", "key", "ping-interval"));

        assertThat(result).isEqualTo(Map.of("ok", true));
        verify(apiClient).adminConfigReset("ping-interval");
    }

    @Test
    void reset_webModule_callsOverrideServiceAndReturnsOk() {
        doNothing().when(webConfigOverrideService).clear(any());

        Object result = handler.handle(adminSession(), Map.of("module", "web", "key", "catapult.foo"));

        assertThat(result).isEqualTo(Map.of("ok", true));
        verify(webConfigOverrideService).clear("catapult.foo");
    }

    @Test
    void reset_nullModule_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("key", "foo")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void reset_blankModule_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "  ", "key", "foo")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void reset_nullKey_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "api")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void reset_blankKey_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "api", "key", "")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void reset_apiClientFails_throwsInternal() {
        when(apiClient.adminConfigReset("k")).thenReturn(false);

        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "api", "key", "k")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("INTERNAL"));
    }

    @Test
    void reset_webModuleTabooKey_throwsForbidden() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Key is taboo"))
                .when(webConfigOverrideService).clear(any());

        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "web", "key", "secret.key")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void reset_webModuleUnknownKey_throwsNotFound() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not exposable"))
                .when(webConfigOverrideService).clear(any());

        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "web", "key", "unknown.key")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void reset_webModuleBadGateway_throwsInternal() {
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "catapult-api refused to clear the override"))
                .when(webConfigOverrideService).clear(any());

        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "web", "key", "catapult.foo")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("INTERNAL"));
    }

    @Test
    void reset_action() {
        assertThat(handler.action()).isEqualTo("admin.config.reset");
    }

    @Test
    void reset_requiresAuth() {
        assertThat(handler.requiresAuth()).isTrue();
    }

    @Test
    void reset_requiresAdmin() {
        assertThat(handler.requiresAdmin()).isTrue();
    }

    @Test
    void reset_throwsWhenUnauthenticated() {
        WsSession session = new WsSession(mock(WebSocketSession.class));

        assertThatThrownBy(() -> handler.handle(session, Map.of("module", "api", "key", "k")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void reset_throwsWhenNotAdmin() {
        assertThatThrownBy(() -> handler.handle(userSession(), Map.of("module", "api", "key", "k")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("FORBIDDEN"));
    }
}
