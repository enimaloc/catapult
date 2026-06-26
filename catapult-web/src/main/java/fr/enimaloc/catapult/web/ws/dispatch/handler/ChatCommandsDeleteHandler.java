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
 * WebSocket replacement for {@code DELETE /chat-commands/api/{id}}.
 * Body: {@code {id}}.
 */
@Component
public class ChatCommandsDeleteHandler implements RequestHandler {

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    public ChatCommandsDeleteHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = JsonMapper.builder().build();
    }

    public record Params(UUID id) {}

    @Override
    public String action() {
        return "chat-commands.delete";
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
    public Object handle(WsSession session, Object rawParams) {
        Params p = rawParams == null ? null : mapper.convertValue(rawParams, Params.class);
        if (p == null || p.id() == null) {
            throw new WsBusinessException("INVALID_PARAMS", "id is required");
        }
        boolean ok = apiClient.delete("/api/chat-commands/{id}", p.id());
        if (!ok) {
            throw new WsBusinessException("UPSTREAM_UNAVAILABLE",
                    "Delete failed (check catapult-api logs — built-ins are not deletable)");
        }
        return Map.of("id", p.id().toString());
    }
}
