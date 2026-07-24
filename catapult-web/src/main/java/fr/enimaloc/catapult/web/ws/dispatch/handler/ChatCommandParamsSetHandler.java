package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Upserts one param. Body: {@code {key, value}}. */
@Component
public class ChatCommandParamsSetHandler implements RequestHandler {

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    public ChatCommandParamsSetHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = JsonMapper.builder().build();
    }

    @Override
    public String action() {
        return "chat-commands.params.set";
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
        Object key = body.get("key");
        Object value = body.get("value");
        if (!(key instanceof String) || value == null) {
            throw new WsBusinessException("INVALID_PARAMS", "key and value are required");
        }
        boolean ok = apiClient.put("/api/chat-command-params/{key}", Map.of("value", value), key);
        if (!ok) {
            throw new WsBusinessException("UPSTREAM_UNAVAILABLE",
                "Save failed (check catapult-api logs — key must match ^[A-Za-z_][A-Za-z0-9_]*$)");
        }
        return Map.of("key", key, "value", value);
    }
}
