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

class ChatCommandSettingsDeleteHandlerTest {

    @Test
    void deletesAndEchoesKey() {
        ApiClient apiClient = mock(ApiClient.class);
        when(apiClient.delete("/api/chat-command-settings/{key}", "language")).thenReturn(true);

        ChatCommandSettingsDeleteHandler handler = new ChatCommandSettingsDeleteHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), Map.of("key", "language"));

        assertThat(result).isEqualTo(Map.of("key", "language"));
        assertThat(handler.action()).isEqualTo("chat-commands.settings.delete");
    }

    @Test
    void missingKeyThrowsInvalidParams() {
        ApiClient apiClient = mock(ApiClient.class);
        ChatCommandSettingsDeleteHandler handler = new ChatCommandSettingsDeleteHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of()))
            .isInstanceOf(WsBusinessException.class);
    }
}
