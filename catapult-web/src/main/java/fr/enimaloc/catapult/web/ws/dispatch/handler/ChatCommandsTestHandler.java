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
 * Backs the "Tester" button in the command editor modal.
 * Body: {@code {id, overrides, ast?, ejectedJs?}} — {@code overrides} is a map of placeholder
 * path -> test value used instead of the live GameContext; {@code ast}/{@code ejectedJs} are
 * the editor's in-progress (possibly unsaved) content, forwarded as-is to
 * {@code ApiChatCommandTestController}, which prefers them over the persisted row.
 */
@Component
public class ChatCommandsTestHandler implements RequestHandler {

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    public ChatCommandsTestHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = JsonMapper.builder().build();
    }

    @Override
    public String action() {
        return "chat-commands.test";
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
        Map<String, Object> result = apiClient.post("/api/chat-commands/{id}/test", body, Map.class, id);
        if (result == null) {
            throw new WsBusinessException("UPSTREAM_UNAVAILABLE", "Test run failed (check catapult-api logs)");
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
