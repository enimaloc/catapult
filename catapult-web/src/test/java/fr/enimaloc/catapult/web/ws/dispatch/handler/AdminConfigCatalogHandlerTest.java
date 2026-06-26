package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.service.config.WebConfigCatalogService;
import fr.enimaloc.catapult.web.service.config.WebConfigEntry;
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

class AdminConfigCatalogHandlerTest {

    private ApiClient apiClient;
    private WebConfigCatalogService webConfigCatalogService;
    private AdminConfigCatalogHandler handler;

    @BeforeEach
    void setUp() {
        apiClient = mock(ApiClient.class);
        webConfigCatalogService = mock(WebConfigCatalogService.class);
        handler = new AdminConfigCatalogHandler(apiClient, webConfigCatalogService);
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
    void catalog_apiModule_callsApiClient() {
        List<Map<String, Object>> expected = List.of(Map.of("key", "foo", "value", "bar"));
        when(apiClient.adminConfigCatalog("api")).thenReturn(expected);

        Object result = handler.handle(adminSession(), Map.of("module", "api"));

        assertThat(result).isEqualTo(expected);
        verify(apiClient).adminConfigCatalog("api");
    }

    @Test
    void catalog_nullModule_defaultsToApi() {
        List<Map<String, Object>> expected = List.of(Map.of("key", "k", "value", "v"));
        when(apiClient.adminConfigCatalog("api")).thenReturn(expected);

        Object result = handler.handle(adminSession(), Map.of());

        assertThat(result).isEqualTo(expected);
        verify(apiClient).adminConfigCatalog("api");
    }

    @Test
    void catalog_blankModule_defaultsToApi() {
        List<Map<String, Object>> expected = List.of();
        when(apiClient.adminConfigCatalog("api")).thenReturn(expected);

        Object result = handler.handle(adminSession(), Map.of("module", "   "));

        assertThat(result).isEqualTo(expected);
        verify(apiClient).adminConfigCatalog("api");
    }

    @Test
    void catalog_webModule_callsCatalogService() {
        WebConfigEntry entry = new WebConfigEntry("catapult.foo", "bar", false, true, false, false, "application.yml");
        when(webConfigCatalogService.catalog()).thenReturn(List.of(entry));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) handler.handle(adminSession(), Map.of("module", "web"));

        assertThat(result).hasSize(1);
        Map<String, Object> m = result.get(0);
        assertThat(m.get("key")).isEqualTo("catapult.foo");
        assertThat(m.get("value")).isEqualTo("bar");
        assertThat(m.get("secret")).isEqualTo(false);
        assertThat(m.get("restartRequired")).isEqualTo(true);
        assertThat(m.get("overridden")).isEqualTo(false);
        assertThat(m.get("taboo")).isEqualTo(false);
        assertThat(m.get("source")).isEqualTo("application.yml");
    }

    @Test
    void catalog_apiReturnsNull_throwsInternal() {
        when(apiClient.adminConfigCatalog("api")).thenReturn(null);

        assertThatThrownBy(() -> handler.handle(adminSession(), Map.of("module", "api")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("INTERNAL"));
    }

    @Test
    void catalog_action() {
        assertThat(handler.action()).isEqualTo("admin.config.catalog");
    }

    @Test
    void catalog_requiresAuth() {
        assertThat(handler.requiresAuth()).isTrue();
    }

    @Test
    void catalog_requiresAdmin() {
        assertThat(handler.requiresAdmin()).isTrue();
    }

    @Test
    void catalog_throwsWhenUnauthenticated() {
        WsSession session = new WsSession(mock(WebSocketSession.class));

        assertThatThrownBy(() -> handler.handle(session, Map.of("module", "api")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void catalog_throwsWhenNotAdmin() {
        assertThatThrownBy(() -> handler.handle(userSession(), Map.of("module", "api")))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(ex -> assertThat(((WsBusinessException) ex).code()).isEqualTo("FORBIDDEN"));
    }
}
