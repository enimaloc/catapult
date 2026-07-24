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

class ChatCommandParamsListHandlerTest {

    @Test
    void returnsTheParamListFromCatapultApi() {
        ApiClient apiClient = mock(ApiClient.class);
        List<Map<String, Object>> params = List.of(Map.of("key", "language", "value", "fr"));
        when(apiClient.get(eq("/api/chat-command-params"), any(ParameterizedTypeReference.class)))
            .thenReturn(params);

        ChatCommandParamsListHandler handler = new ChatCommandParamsListHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), null);

        assertThat(result).isEqualTo(params);
        assertThat(handler.action()).isEqualTo("chat-commands.params.list");
    }
}
