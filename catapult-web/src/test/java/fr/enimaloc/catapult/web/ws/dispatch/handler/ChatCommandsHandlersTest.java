package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCommandsHandlersTest {

    @Test
    @SuppressWarnings("unchecked")
    void list_returns_upstream_payload() throws Exception {
        ApiClient api = mock(ApiClient.class);
        when(api.get(eq("/api/chat-commands"), any(ParameterizedTypeReference.class)))
                .thenReturn(Map.of("commands", java.util.List.of(), "presets", java.util.List.of()));
        var handler = new ChatCommandsListHandler(api);

        Object out = handler.handle((WsSession) null, null);

        assertThat(out).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) out).containsKey("commands");
    }

    @Test
    void list_null_upstream_throws() {
        ApiClient api = mock(ApiClient.class);
        when(api.get(eq("/api/chat-commands"), any(ParameterizedTypeReference.class)))
                .thenReturn(null);
        var handler = new ChatCommandsListHandler(api);

        assertThatThrownBy(() -> handler.handle((WsSession) null, null))
                .isInstanceOf(WsBusinessException.class)
                .hasMessageContaining("Upstream call failed");
    }

    @Test
    void create_posts_body_and_returns_dto() throws Exception {
        ApiClient api = mock(ApiClient.class);
        Map<String, Object> created = Map.of("id", UUID.randomUUID().toString(), "name", "!hi");
        when(api.post(eq("/api/chat-commands"), any(), eq(Map.class))).thenReturn(created);
        var handler = new ChatCommandsCreateHandler(api);

        Object out = handler.handle((WsSession) null, Map.of(
                "name", "!hi", "template", "hello", "permission", "EVERYONE",
                "enabled", true, "fallbacks", Map.of()));

        assertThat(out).isEqualTo(created);
    }

    @Test
    void create_null_params_rejected() {
        var handler = new ChatCommandsCreateHandler(mock(ApiClient.class));
        assertThatThrownBy(() -> handler.handle((WsSession) null, null))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void update_strips_id_from_body_and_uses_it_in_path() throws Exception {
        ApiClient api = mock(ApiClient.class);
        UUID id = UUID.randomUUID();
        when(api.put(eq("/api/chat-commands/{id}"), any(), eq(Map.class), eq(id)))
                .thenReturn(Map.of("id", id.toString(), "name", "!hi"));
        var handler = new ChatCommandsUpdateHandler(api);

        Object out = handler.handle((WsSession) null, Map.of(
                "id", id.toString(),
                "name", "!hi", "template", "hello", "permission", "EVERYONE",
                "enabled", true, "fallbacks", Map.of()));

        assertThat(out).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) out).get("id")).isEqualTo(id.toString());
        // body forwarded to ApiClient must NOT contain the id field — it goes
        // in the URL only.
        verify(api).put(eq("/api/chat-commands/{id}"),
                org.mockito.ArgumentMatchers.argThat(b -> !((Map<?, ?>) b).containsKey("id")),
                eq(Map.class), eq(id));
    }

    @Test
    void update_invalid_id_rejected() {
        var handler = new ChatCommandsUpdateHandler(mock(ApiClient.class));
        assertThatThrownBy(() -> handler.handle((WsSession) null, Map.of("id", "not-a-uuid")))
                .isInstanceOf(WsBusinessException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void delete_forwards_id_to_api() throws Exception {
        ApiClient api = mock(ApiClient.class);
        UUID id = UUID.randomUUID();
        when(api.delete(eq("/api/chat-commands/{id}"), eq(id))).thenReturn(true);
        var handler = new ChatCommandsDeleteHandler(api);

        Object out = handler.handle((WsSession) null, Map.of("id", id.toString()));

        assertThat(((Map<?, ?>) out).get("id")).isEqualTo(id.toString());
    }

    @Test
    void delete_upstream_failure_throws() {
        ApiClient api = mock(ApiClient.class);
        UUID id = UUID.randomUUID();
        when(api.delete(eq("/api/chat-commands/{id}"), eq(id))).thenReturn(false);
        var handler = new ChatCommandsDeleteHandler(api);

        assertThatThrownBy(() -> handler.handle((WsSession) null, Map.of("id", id.toString())))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void preset_instantiate_routes_to_api() throws Exception {
        ApiClient api = mock(ApiClient.class);
        Map<String, Object> created = Map.of("id", UUID.randomUUID().toString());
        when(api.post(eq("/api/chat-commands/presets/{key}"), isNull(), eq(Map.class), eq("game")))
                .thenReturn(created);
        var handler = new ChatCommandsPresetInstantiateHandler(api);

        Object out = handler.handle((WsSession) null, Map.of("key", "game"));

        assertThat(out).isEqualTo(created);
    }

    @Test
    void preset_instantiate_blank_key_rejected() {
        var handler = new ChatCommandsPresetInstantiateHandler(mock(ApiClient.class));
        assertThatThrownBy(() -> handler.handle((WsSession) null, Map.of("key", " ")))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void action_names_and_metadata() {
        ApiClient api = mock(ApiClient.class);
        assertThat(new ChatCommandsListHandler(api).action()).isEqualTo("chat-commands.list");
        assertThat(new ChatCommandsCreateHandler(api).action()).isEqualTo("chat-commands.create");
        assertThat(new ChatCommandsUpdateHandler(api).action()).isEqualTo("chat-commands.update");
        assertThat(new ChatCommandsDeleteHandler(api).action()).isEqualTo("chat-commands.delete");
        assertThat(new ChatCommandsPresetInstantiateHandler(api).action())
                .isEqualTo("chat-commands.preset.instantiate");

        // All require auth, none admin.
        for (var h : java.util.List.of(
                new ChatCommandsListHandler(api),
                new ChatCommandsCreateHandler(api),
                new ChatCommandsUpdateHandler(api),
                new ChatCommandsDeleteHandler(api),
                new ChatCommandsPresetInstantiateHandler(api))) {
            assertThat(h.requiresAuth()).isTrue();
            assertThat(h.requiresAdmin()).isFalse();
        }
    }
}
