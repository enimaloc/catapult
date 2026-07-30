package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatCommandsDslAstToTextHandlerTest {

    @Test
    void postsAstAndReturnsText() {
        ApiClient apiClient = mock(ApiClient.class);
        Map<String, Object> apiResult = Map.of("text", "Hello");
        when(apiClient.post(eq("/api/chat-commands/dsl/ast-to-text"), any(), eq(Map.class)))
                .thenReturn(apiResult);

        ChatCommandsDslAstToTextHandler handler = new ChatCommandsDslAstToTextHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), Map.of("ast", "{\"nodes\":[]}"));

        assertThat(result).isEqualTo(apiResult);
    }

    @Test
    void nullResultThrowsUpstreamUnavailable() {
        ApiClient apiClient = mock(ApiClient.class);
        when(apiClient.post(eq("/api/chat-commands/dsl/ast-to-text"), any(), eq(Map.class)))
                .thenReturn(null);

        ChatCommandsDslAstToTextHandler handler = new ChatCommandsDslAstToTextHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of("ast", "{\"nodes\":[]}")))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void springErrorBodyWithoutATextKeyThrowsInvalidDslWithTheUpstreamMessage() {
        // ApiClient's non-throwing 4xx status handler means a 400 from catapult-api's
        // validation comes back here as Spring's default error body, not null and not
        // a {text:...} map.
        ApiClient apiClient = mock(ApiClient.class);
        Map<String, Object> springErrorBody = Map.of(
                "timestamp", "2026-07-23T00:00:00Z",
                "status", 400,
                "error", "Bad Request",
                "message", "Unclosed '{' starting at position 0",
                "path", "/api/chat-commands/dsl/ast-to-text");
        when(apiClient.post(eq("/api/chat-commands/dsl/ast-to-text"), any(), eq(Map.class)))
                .thenReturn(springErrorBody);

        ChatCommandsDslAstToTextHandler handler = new ChatCommandsDslAstToTextHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of("ast", "not json")))
                .isInstanceOf(WsBusinessException.class)
                .hasMessageContaining("Unclosed '{' starting at position 0");
    }

    @Test
    void nullBodyThrowsInvalidParams() {
        ChatCommandsDslAstToTextHandler handler = new ChatCommandsDslAstToTextHandler(mock(ApiClient.class));

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), null))
                .isInstanceOf(WsBusinessException.class);
    }
}
