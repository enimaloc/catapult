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
 * Backs the editor modal's Text-&gt;Blocks conversion: the DSL grammar (parser/generator)
 * only exists in {@code catapult-api}, so the browser round-trips through this WS action
 * rather than duplicating the grammar in JavaScript.
 * Body: {@code {text}}.
 */
@Component
public class ChatCommandsDslTextToAstHandler implements RequestHandler {

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    public ChatCommandsDslTextToAstHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = JsonMapper.builder().build();
    }

    @Override
    public String action() {
        return "chat-commands.dsl.text-to-ast";
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
        Map<String, Object> result = apiClient.post("/api/chat-commands/dsl/text-to-ast", body, Map.class);
        if (result == null) {
            throw new WsBusinessException("UPSTREAM_UNAVAILABLE", "Conversion failed (check catapult-api logs)");
        }
        return result;
    }
}
