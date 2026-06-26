package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * WebSocket replacement for {@code POST /chat-commands/api/presets/{key}}.
 * Params: {@code {key}}.
 */
@Component
public class ChatCommandsPresetInstantiateHandler implements RequestHandler {

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    public ChatCommandsPresetInstantiateHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = JsonMapper.builder().build();
    }

    public record Params(String key) {}

    @Override
    public String action() {
        return "chat-commands.preset.instantiate";
    }

    @Override
    public boolean requiresAuth() {
        return true;
    }

    @Override
    public boolean requiresAdmin() {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object handle(WsSession session, Object rawParams) {
        Params p = rawParams == null ? null : mapper.convertValue(rawParams, Params.class);
        if (p == null || p.key() == null || p.key().isBlank()) {
            throw new WsBusinessException("INVALID_PARAMS", "key is required");
        }
        Map<String, Object> created = apiClient.post(
                "/api/chat-commands/presets/{key}", null, Map.class, p.key());
        if (created == null) {
            throw new WsBusinessException("UPSTREAM_UNAVAILABLE",
                    "Preset instantiation failed (check catapult-api logs)");
        }
        return created;
    }
}
