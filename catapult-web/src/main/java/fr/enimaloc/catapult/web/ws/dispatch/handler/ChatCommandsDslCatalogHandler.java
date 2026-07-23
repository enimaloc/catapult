package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Backs the editor modal's Blockly toolbox/dropdown catalog: the known context paths and
 * registered service functions live server-side ({@code PlaceholderResolver#KNOWN_PATHS},
 * {@code ServiceFunctionRegistry}) so the server stays authoritative for what a streamer can
 * build — the client renders whatever this reports instead of hardcoding its own copy.
 * No body required.
 */
@Component
public class ChatCommandsDslCatalogHandler implements RequestHandler {

    private static final ParameterizedTypeReference<Map<String, Object>> CATALOG_TYPE =
        new ParameterizedTypeReference<>() {};

    private final ApiClient apiClient;

    public ChatCommandsDslCatalogHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String action() {
        return "chat-commands.dsl.catalog";
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
        Map<String, Object> result = apiClient.get("/api/chat-commands/dsl/catalog", CATALOG_TYPE);
        if (result == null || !result.containsKey("contextPaths")) {
            throw new WsBusinessException("UPSTREAM_UNAVAILABLE",
                "Catalog fetch failed (check catapult-api logs)");
        }
        return result;
    }
}
