package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Lists the current streamer's chat-command params. No body required. */
@Component
public class ChatCommandParamsListHandler implements RequestHandler {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> PARAMS_TYPE =
        new ParameterizedTypeReference<>() {};

    private final ApiClient apiClient;

    public ChatCommandParamsListHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String action() {
        return "chat-commands.params.list";
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
        return apiClient.get("/api/chat-command-params", PARAMS_TYPE);
    }
}
