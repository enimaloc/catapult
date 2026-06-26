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
 * WebSocket replacement for {@code POST /chat-commands/api}.
 * Body shape mirrors {@code ApiChatCommandsController.UpsertRequest}:
 * {@code {name, template, permission, enabled, fallbacks}}.
 */
@Component
public class ChatCommandsCreateHandler implements RequestHandler {

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    public ChatCommandsCreateHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = JsonMapper.builder().build();
    }

    @Override
    public String action() {
        return "chat-commands.create";
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
        Map<String, Object> created = apiClient.post(
                "/api/chat-commands", body, Map.class);
        if (created == null) {
            throw new WsBusinessException("UPSTREAM_UNAVAILABLE",
                    "Create failed (check catapult-api logs — likely 409 name conflict or 400 invalid placeholder)");
        }
        return created;
    }
}
