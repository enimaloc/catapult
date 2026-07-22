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

class ChatCommandsDslTextToAstHandlerTest {

    @Test
    void postsTextAndReturnsAst() {
        ApiClient apiClient = mock(ApiClient.class);
        Map<String, Object> apiResult = Map.of("ast", "{\"nodes\":[]}");
        when(apiClient.post(eq("/api/chat-commands/dsl/text-to-ast"), any(), eq(Map.class)))
                .thenReturn(apiResult);

        ChatCommandsDslTextToAstHandler handler = new ChatCommandsDslTextToAstHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), Map.of("text", "Hello"));

        assertThat(result).isEqualTo(apiResult);
    }

    @Test
    void nullResultThrowsUpstreamUnavailable() {
        ApiClient apiClient = mock(ApiClient.class);
        when(apiClient.post(eq("/api/chat-commands/dsl/text-to-ast"), any(), eq(Map.class)))
                .thenReturn(null);

        ChatCommandsDslTextToAstHandler handler = new ChatCommandsDslTextToAstHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of("text", "Hello")))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void nullBodyThrowsInvalidParams() {
        ChatCommandsDslTextToAstHandler handler = new ChatCommandsDslTextToAstHandler(mock(ApiClient.class));

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), null))
                .isInstanceOf(WsBusinessException.class);
    }
}
