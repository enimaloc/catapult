package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Backs the Tester panel's "import a game from IGDB" search box. Body: {@code {q}}.
 * Non-admin-gated equivalent of the {@code search.game} action, which is reserved for the
 * admin members page — regular streamers use the chat-command editor too.
 */
@Component
public class ChatCommandsDslIgdbSearchHandler implements RequestHandler {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> RESULTS_TYPE =
        new ParameterizedTypeReference<>() {};

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    public ChatCommandsDslIgdbSearchHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = JsonMapper.builder().build();
    }

    @Override
    public String action() {
        return "chat-commands.dsl.igdb-search";
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
        Object q = body.get("q");
        if (!(q instanceof String qs) || qs.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> result = apiClient.get("/api/chat-commands/dsl/igdb-search?q={q}", RESULTS_TYPE, qs);
        return result == null ? List.of() : result;
    }
}
