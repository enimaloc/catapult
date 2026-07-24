package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * WebSocket replacement for {@code PUT /chat-commands/api/{id}}.
 * Body: {@code {id, name, template, permission, enabled, fallbacks}}.
 */
@Component
public class ChatCommandsUpdateHandler implements RequestHandler {

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    public ChatCommandsUpdateHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = JsonMapper.builder().build();
    }

    @Override
    public String action() {
        return "chat-commands.update";
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
        if (rawParams == null) {
            throw new WsBusinessException("INVALID_PARAMS", "body is required");
        }
        Map<String, Object> body = mapper.convertValue(rawParams, Map.class);
        Object idRaw = body.remove("id");
        UUID id = parseId(idRaw);
        Map<String, Object> result = apiClient.put("/api/chat-commands/{id}", body, Map.class, id);
        if (result == null) {
            throw new WsBusinessException("UPSTREAM_UNAVAILABLE",
                    "Update failed (check catapult-api logs)");
        }
        return result;
    }

    private static UUID parseId(Object raw) {
        if (raw == null) {
            throw new WsBusinessException("INVALID_PARAMS", "id is required");
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            throw new WsBusinessException("INVALID_PARAMS", "id is not a valid UUID");
        }
    }
}
