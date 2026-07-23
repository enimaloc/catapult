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

class ChatCommandsDslAstToJsHandlerTest {

    @Test
    void postsAstAndReturnsJs() {
        ApiClient apiClient = mock(ApiClient.class);
        Map<String, Object> apiResult = Map.of("js", "let __output = \"\";\nreturn __output;\n");
        when(apiClient.post(eq("/api/chat-commands/dsl/ast-to-js"), any(), eq(Map.class)))
                .thenReturn(apiResult);

        ChatCommandsDslAstToJsHandler handler = new ChatCommandsDslAstToJsHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), Map.of("ast", "{\"statements\":[]}"));

        assertThat(result).isEqualTo(apiResult);
    }

    @Test
    void nullResultThrowsUpstreamUnavailable() {
        ApiClient apiClient = mock(ApiClient.class);
        when(apiClient.post(eq("/api/chat-commands/dsl/ast-to-js"), any(), eq(Map.class)))
                .thenReturn(null);

        ChatCommandsDslAstToJsHandler handler = new ChatCommandsDslAstToJsHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of("ast", "{\"statements\":[]}")))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void springErrorBodyWithoutAJsKeyThrowsInvalidDslWithTheUpstreamMessage() {
        // ApiClient's non-throwing 4xx status handler means a 400 from catapult-api's
        // validation comes back here as Spring's default error body, not null and not
        // a {js:...} map.
        ApiClient apiClient = mock(ApiClient.class);
        Map<String, Object> springErrorBody = Map.of(
                "timestamp", "2026-07-23T00:00:00Z",
                "status", 400,
                "error", "Bad Request",
                "message", "Malformed expression: bogus",
                "path", "/api/chat-commands/dsl/ast-to-js");
        when(apiClient.post(eq("/api/chat-commands/dsl/ast-to-js"), any(), eq(Map.class)))
                .thenReturn(springErrorBody);

        ChatCommandsDslAstToJsHandler handler = new ChatCommandsDslAstToJsHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of("ast", "not json")))
                .isInstanceOf(WsBusinessException.class)
                .hasMessageContaining("Malformed expression: bogus");
    }

    @Test
    void nullBodyThrowsInvalidParams() {
        ChatCommandsDslAstToJsHandler handler = new ChatCommandsDslAstToJsHandler(mock(ApiClient.class));

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), null))
                .isInstanceOf(WsBusinessException.class);
    }
}
