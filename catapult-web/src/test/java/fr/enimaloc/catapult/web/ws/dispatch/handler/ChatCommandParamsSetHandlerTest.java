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

class ChatCommandParamsSetHandlerTest {

    @Test
    void putsTheValueAndEchoesKeyAndValue() {
        ApiClient apiClient = mock(ApiClient.class);
        when(apiClient.put("/api/chat-command-params/{key}", Map.of("value", "fr"), "language")).thenReturn(true);

        ChatCommandParamsSetHandler handler = new ChatCommandParamsSetHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), Map.of("key", "language", "value", "fr"));

        assertThat(result).isEqualTo(Map.of("key", "language", "value", "fr"));
        assertThat(handler.action()).isEqualTo("chat-commands.params.set");
    }

    @Test
    void missingKeyThrowsInvalidParams() {
        ApiClient apiClient = mock(ApiClient.class);
        ChatCommandParamsSetHandler handler = new ChatCommandParamsSetHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of("value", "fr")))
            .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void upstreamFailureThrowsUpstreamUnavailable() {
        ApiClient apiClient = mock(ApiClient.class);
        when(apiClient.put("/api/chat-command-params/{key}", Map.of("value", "fr"), "language")).thenReturn(false);

        ChatCommandParamsSetHandler handler = new ChatCommandParamsSetHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of("key", "language", "value", "fr")))
            .isInstanceOf(WsBusinessException.class);
    }
}
