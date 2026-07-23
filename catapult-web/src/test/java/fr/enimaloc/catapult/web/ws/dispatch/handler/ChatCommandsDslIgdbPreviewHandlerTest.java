package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatCommandsDslIgdbPreviewHandlerTest {

    @Test
    void postsIdAndNameAndReturnsResolvedPlaceholders() {
        ApiClient apiClient = mock(ApiClient.class);
        Map<String, Object> apiResult = Map.of("game#name", "Zelda", "game#summary", "An adventure");
        when(apiClient.get(eq("/api/chat-commands/dsl/igdb-preview?id={id}&name={name}"),
                any(ParameterizedTypeReference.class), eq("123"), eq("Zelda")))
                .thenReturn(apiResult);

        ChatCommandsDslIgdbPreviewHandler handler = new ChatCommandsDslIgdbPreviewHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), Map.of("id", "123", "name", "Zelda"));

        assertThat(result).isEqualTo(apiResult);
    }

    @Test
    void nullUpstreamResultReturnsEmptyMap() {
        ApiClient apiClient = mock(ApiClient.class);
        when(apiClient.get(eq("/api/chat-commands/dsl/igdb-preview?id={id}&name={name}"),
                any(ParameterizedTypeReference.class), eq("123"), eq("Zelda")))
                .thenReturn(null);

        ChatCommandsDslIgdbPreviewHandler handler = new ChatCommandsDslIgdbPreviewHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), Map.of("id", "123", "name", "Zelda"));

        assertThat(result).isEqualTo(Map.of());
    }

    @Test
    void missingIdOrNameThrowsInvalidParams() {
        ChatCommandsDslIgdbPreviewHandler handler = new ChatCommandsDslIgdbPreviewHandler(mock(ApiClient.class));

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of("id", "123")))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void nullBodyThrowsInvalidParams() {
        ChatCommandsDslIgdbPreviewHandler handler = new ChatCommandsDslIgdbPreviewHandler(mock(ApiClient.class));

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), null))
                .isInstanceOf(WsBusinessException.class);
    }
}
