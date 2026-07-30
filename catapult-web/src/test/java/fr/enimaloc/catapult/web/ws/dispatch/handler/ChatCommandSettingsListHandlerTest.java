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
import static org.mockito.Mockito.when;

class ChatCommandSettingsListHandlerTest {

    @Test
    void returnsTheSettingListFromCatapultApi() {
        ApiClient apiClient = mock(ApiClient.class);
        List<Map<String, Object>> settings = List.of(Map.of("key", "language", "value", "fr"));
        when(apiClient.get(eq("/api/chat-command-settings"), any(ParameterizedTypeReference.class)))
            .thenReturn(settings);

        ChatCommandSettingsListHandler handler = new ChatCommandSettingsListHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), null);

        assertThat(result).isEqualTo(settings);
        assertThat(handler.action()).isEqualTo("chat-commands.settings.list");
    }
}
