package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatCommandsDslCatalogHandlerTest {

    @Test
    void returnsTheCatalogFromCatapultApi() {
        ApiClient apiClient = mock(ApiClient.class);
        Map<String, Object> apiResult = Map.of(
                "contextPaths", List.of("game#name"),
                "serviceFunctions", List.of(Map.of("namespace", "igdb", "name", "getGame", "parameterNames", List.of("query"))));
        when(apiClient.get(eq("/api/chat-commands/dsl/catalog"), any(ParameterizedTypeReference.class)))
                .thenReturn(apiResult);

        ChatCommandsDslCatalogHandler handler = new ChatCommandsDslCatalogHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), null);

        assertThat(result).isEqualTo(apiResult);
    }

    @Test
    void nullResultThrowsUpstreamUnavailable() {
        ApiClient apiClient = mock(ApiClient.class);
        when(apiClient.get(eq("/api/chat-commands/dsl/catalog"), any(ParameterizedTypeReference.class)))
                .thenReturn(null);

        ChatCommandsDslCatalogHandler handler = new ChatCommandsDslCatalogHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), null))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void resultWithoutContextPathsKeyThrowsUpstreamUnavailable() {
        // Same ApiClient non-throwing-4xx pattern as the other DSL handlers: a failure can
        // come back as Spring's default error body instead of null.
        ApiClient apiClient = mock(ApiClient.class);
        Map<String, Object> springErrorBody = Map.of("status", 403, "error", "Forbidden");
        when(apiClient.get(eq("/api/chat-commands/dsl/catalog"), any(ParameterizedTypeReference.class)))
                .thenReturn(springErrorBody);

        ChatCommandsDslCatalogHandler handler = new ChatCommandsDslCatalogHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), null))
                .isInstanceOf(WsBusinessException.class);
    }
}
