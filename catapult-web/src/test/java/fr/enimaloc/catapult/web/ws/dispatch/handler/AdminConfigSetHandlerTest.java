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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminConfigSetHandlerTest {

    private ApiClient apiClient;
    private WebConfigOverrideService webConfigOverrideService;
    private AdminConfigSetHandler handler;

    @BeforeEach
    void setUp() {
        apiClient = mock(ApiClient.class);
        webConfigOverrideService = mock(WebConfigOverrideService.class);
        handler = new AdminConfigSetHandler(apiClient, webConfigOverrideService);
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
    void set_apiModule_callsApiClientAndReturnsOk() {
        when(apiClient.adminConfigSet("ping-interval", "30")).thenReturn(true);

        Object result = handler.handle(adminSession(), Map.of("module", "api", "key", "ping-interval", "value", "30"));

        assertThat(result).isEqualTo(Map.of("ok", true));
        verify(apiClient).adminConfigSet("ping-interval", "30");
    }

    @Test
    void set_webModule_callsOverrideServiceAndReturnsOk() {
        doNothing().when(webConfigOverrideService).apply(eq("catapult.foo"), any());

        Object result = handler.handle(adminSession(), Map.of("module", "web", "key", "catapult.foo", "value", "newval"));

        assertThat(result).isEqualTo(Map.of("ok", true));
        verify(webConfigOverrideService).apply("catapult.foo", "newval");
    }

    @Test
    void set_nullModule_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("key", "foo", "value", "bar")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void set_blankModule_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "  ", "key", "foo", "value", "bar")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void set_nullKey_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "api", "value", "bar")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void set_blankKey_throwsValidation() {
        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "api", "key", "", "value", "bar")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void set_apiClientFails_throwsInternal() {
        when(apiClient.adminConfigSet("k", "v")).thenReturn(false);

        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "api", "key", "k", "value", "v")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("INTERNAL"));
    }

    @Test
    void set_webModuleTabooKey_throwsForbidden() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Key is taboo"))
                .when(webConfigOverrideService).apply(any(), any());

        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "web", "key", "secret.key", "value", "x")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void set_webModuleUnknownKey_throwsNotFound() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not exposable"))
                .when(webConfigOverrideService).apply(any(), any());

        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "web", "key", "unknown.key", "value", "x")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void set_webModuleBadGateway_throwsInternal() {
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "catapult-api refused the override"))
                .when(webConfigOverrideService).apply(any(), any());

        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "web", "key", "catapult.foo", "value", "x")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("INTERNAL"));
    }

    @Test
    void set_action() {
        assertThat(handler.action()).isEqualTo("admin.config.set");
    }

    @Test
    void set_requiresAuth() {
        assertThat(handler.requiresAuth()).isTrue();
    }

    @Test
    void set_requiresAdmin() {
        assertThat(handler.requiresAdmin()).isTrue();
    }

    @Test
    void set_throwsWhenUnauthenticated() {
        WsSession session = new WsSession(mock(WebSocketSession.class));

        assertThatThrownBy(() -> handler.handle(session, Map.of("module", "api", "key", "k", "value", "v")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void set_throwsWhenNotAdmin() {
        assertThatThrownBy(() -> handler.handle(userSession(), Map.of("module", "api", "key", "k", "value", "v")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("FORBIDDEN"));
    }
}
