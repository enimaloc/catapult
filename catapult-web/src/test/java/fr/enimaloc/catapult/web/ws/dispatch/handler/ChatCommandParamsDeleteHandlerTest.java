package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatCommandParamsDeleteHandlerTest {

    @Test
    void deletesAndEchoesKey() {
        ApiClient apiClient = mock(ApiClient.class);
        when(apiClient.delete("/api/chat-command-params/{key}", "language")).thenReturn(true);

        ChatCommandParamsDeleteHandler handler = new ChatCommandParamsDeleteHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), Map.of("key", "language"));

        assertThat(result).isEqualTo(Map.of("key", "language"));
        assertThat(handler.action()).isEqualTo("chat-commands.params.delete");
    }

    @Test
    void missingKeyThrowsInvalidParams() {
        ApiClient apiClient = mock(ApiClient.class);
        ChatCommandParamsDeleteHandler handler = new ChatCommandParamsDeleteHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of()))
            .isInstanceOf(WsBusinessException.class);
    }
}
