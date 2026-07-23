package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatCommandsDslIgdbSearchHandlerTest {

    @Test
    void blankQueryReturnsEmptyListWithoutCallingUpstream() {
        ApiClient apiClient = mock(ApiClient.class);
        ChatCommandsDslIgdbSearchHandler handler = new ChatCommandsDslIgdbSearchHandler(apiClient);

        Object result = handler.handle(mock(WsSession.class), Map.of("q", ""));

        assertThat(result).isEqualTo(List.of());
        verifyNoInteractions(apiClient);
    }

    @Test
    void postsQueryAndReturnsResults() {
        ApiClient apiClient = mock(ApiClient.class);
        List<Map<String, Object>> apiResult = List.of(Map.of("id", "1", "name", "Zelda"));
        when(apiClient.get(eq("/api/chat-commands/dsl/igdb-search?q={q}"), any(ParameterizedTypeReference.class), eq("zelda")))
                .thenReturn(apiResult);

        ChatCommandsDslIgdbSearchHandler handler = new ChatCommandsDslIgdbSearchHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), Map.of("q", "zelda"));

        assertThat(result).isEqualTo(apiResult);
    }

    @Test
    void nullUpstreamResultReturnsEmptyList() {
        ApiClient apiClient = mock(ApiClient.class);
        when(apiClient.get(eq("/api/chat-commands/dsl/igdb-search?q={q}"), any(ParameterizedTypeReference.class), eq("zelda")))
                .thenReturn(null);

        ChatCommandsDslIgdbSearchHandler handler = new ChatCommandsDslIgdbSearchHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), Map.of("q", "zelda"));

        assertThat(result).isEqualTo(List.of());
    }
}
