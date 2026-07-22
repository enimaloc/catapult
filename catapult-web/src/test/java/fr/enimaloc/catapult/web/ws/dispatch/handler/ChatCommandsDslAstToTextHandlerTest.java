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
    void nullBodyThrowsInvalidParams() {
        ChatCommandsDslAstToTextHandler handler = new ChatCommandsDslAstToTextHandler(mock(ApiClient.class));

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), null))
                .isInstanceOf(WsBusinessException.class);
    }
}
