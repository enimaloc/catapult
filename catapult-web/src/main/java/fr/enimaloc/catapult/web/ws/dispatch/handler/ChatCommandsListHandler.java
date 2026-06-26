package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WebSocket replacement for {@code GET /chat-commands/api}.
 * Returns the upstream API payload as-is: {@code {presets, commands, botModStatus}}.
 */
@Component
public class ChatCommandsListHandler implements RequestHandler {

    private final ApiClient apiClient;

    public ChatCommandsListHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String action() {
        return "chat-commands.list";
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
        Map<String, Object> data = apiClient.get(
                "/api/chat-commands", new ParameterizedTypeReference<>() {});
        if (data == null) {
            // ApiClient swallows upstream 4xx/5xx and returns null. The most
            // common cause is the chat.commands experiment gate (403) — same
            // message the legacy proxy surfaced.
            throw new WsBusinessException("UPSTREAM_UNAVAILABLE",
                    "Upstream call failed (check catapult-api logs; likely experiment gate)");
        }
        return data;
    }
}
