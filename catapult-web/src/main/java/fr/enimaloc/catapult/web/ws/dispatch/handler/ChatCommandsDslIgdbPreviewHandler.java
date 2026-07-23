package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Backs the Tester panel's "import a game from IGDB" pre-fill: given a picked search result,
 * resolves every {@code game#*} placeholder for it (via {@code PlaceholderResolver}, same
 * logic as real dispatch) so the "Valeurs de test" fields can be populated automatically.
 * Body: {@code {id, name}}.
 */
@Component
public class ChatCommandsDslIgdbPreviewHandler implements RequestHandler {

    private static final ParameterizedTypeReference<Map<String, Object>> PREVIEW_TYPE =
        new ParameterizedTypeReference<>() {};

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    public ChatCommandsDslIgdbPreviewHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = JsonMapper.builder().build();
    }

    @Override
    public String action() {
        return "chat-commands.dsl.igdb-preview";
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
        Object id = body.get("id");
        Object name = body.get("name");
        if (!(id instanceof String) || !(name instanceof String)) {
            throw new WsBusinessException("INVALID_PARAMS", "id and name are required");
        }
        Map<String, Object> result = apiClient.get(
            "/api/chat-commands/dsl/igdb-preview?id={id}&name={name}", PREVIEW_TYPE, id, name);
        return result == null ? Map.of() : result;
    }
}
