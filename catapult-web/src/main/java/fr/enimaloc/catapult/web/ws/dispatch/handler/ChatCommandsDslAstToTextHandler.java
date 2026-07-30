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
 * Backs the editor modal's Blocks-&gt;Text conversion. Body: {@code {ast}} (NodeJsonCodec JSON).
 */
@Component
public class ChatCommandsDslAstToTextHandler implements RequestHandler {

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    public ChatCommandsDslAstToTextHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = JsonMapper.builder().build();
    }

    @Override
    public String action() {
        return "chat-commands.dsl.ast-to-text";
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
        Map<String, Object> result = apiClient.post("/api/chat-commands/dsl/ast-to-text", body, Map.class);
        // ApiClient installs a non-throwing 4xx status handler (see ApiClient's constructor),
        // so a 400 from catapult-api's validation doesn't come back as null here — it comes
        // back as Spring's default error body ({timestamp,status,error,message,path}), which
        // has no "text" key. Detect that shape instead of assuming any non-null result is valid.
        if (result == null || !result.containsKey("text")) {
            String message = result != null && result.get("message") instanceof String m
                ? m : "Conversion failed (check catapult-api logs)";
            throw new WsBusinessException("INVALID_DSL", message);
        }
        return result;
    }
}
