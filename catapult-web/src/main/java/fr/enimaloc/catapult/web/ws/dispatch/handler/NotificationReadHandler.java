package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * WebSocket replacement for {@code POST /notifications/api/{id}/read}.
 *
 * <p>Modelled as a {@code command} by convention (no response is needed on the
 * wire) but supports being invoked as a {@code request} too — the dispatcher
 * never distinguishes the two on the handler side, only the response shape.</p>
 */
@Component
public class NotificationReadHandler implements RequestHandler {

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    @Autowired
    public NotificationReadHandler(ApiClient apiClient) {
        this(apiClient, JsonMapper.builder().build());
    }

    NotificationReadHandler(ApiClient apiClient, ObjectMapper mapper) {
        this.apiClient = apiClient;
        this.mapper = mapper;
    }

    public record Params(UUID notificationId) {
    }

    @Override
    public String action() {
        return "notification.read";
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
        Params p = rawParams == null ? null : mapper.convertValue(rawParams, Params.class);
        if (p == null || p.notificationId() == null) {
            throw new WsBusinessException("INVALID_PARAMS", "notificationId is required");
        }
        apiClient.post("/api/notifications/{id}/read", null, p.notificationId());
        return null;
    }
}
