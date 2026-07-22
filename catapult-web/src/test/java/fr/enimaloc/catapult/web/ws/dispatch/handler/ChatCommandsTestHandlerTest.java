package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatCommandsTestHandlerTest {

    @Test
    void postsToTestEndpointAndReturnsResult() {
        ApiClient apiClient = mock(ApiClient.class);
        UUID id = UUID.randomUUID();
        Map<String, Object> apiResult = Map.of("output", "Now playing Valorant!", "trace", Map.of());
        when(apiClient.post(eq("/api/chat-commands/{id}/test"), any(), eq(Map.class), eq(id)))
                .thenReturn(apiResult);

        ChatCommandsTestHandler handler = new ChatCommandsTestHandler(apiClient);
        Object result = handler.handle(mock(WsSession.class), Map.of("id", id.toString(), "overrides", Map.of()));

        assertThat(result).isEqualTo(apiResult);
    }

    @Test
    void nullResultFromApiThrowsUpstreamUnavailable() {
        ApiClient apiClient = mock(ApiClient.class);
        UUID id = UUID.randomUUID();
        when(apiClient.post(eq("/api/chat-commands/{id}/test"), any(), eq(Map.class), eq(id)))
                .thenReturn(null);

        ChatCommandsTestHandler handler = new ChatCommandsTestHandler(apiClient);

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of("id", id.toString())))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void missingIdThrowsInvalidParams() {
        ChatCommandsTestHandler handler = new ChatCommandsTestHandler(mock(ApiClient.class));

        assertThatThrownBy(() -> handler.handle(mock(WsSession.class), Map.of("overrides", Map.of())))
                .isInstanceOf(WsBusinessException.class);
    }
}
