package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * WebSocket handler for the {@code admin.notification.delete} action.
 *
 * <p>Deletes a notification by its ID. Throws {@code NOT_FOUND} if the
 * upstream API reports the notification does not exist.</p>
 */
@Component
public class AdminNotificationDeleteHandler implements RequestHandler {

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    @Autowired
    public AdminNotificationDeleteHandler(ApiClient apiClient) {
        this(apiClient, JsonMapper.builder().build());
    }

    AdminNotificationDeleteHandler(ApiClient apiClient, ObjectMapper mapper) {
        this.apiClient = apiClient;
        this.mapper = mapper;
    }

    public record Params(UUID notificationId) {}

    @Override
    public String action() {
        return "admin.notification.delete";
    }

    @Override
    public boolean requiresAuth() {
        return true;
    }

    @Override
    public boolean requiresAdmin() {
        return true;
    }

    @Override
    public Object handle(WsSession session, Object rawParams) {
        session.userId().orElseThrow(
                () -> new WsBusinessException("UNAUTHENTICATED", "Authentication required"));
        if (!session.roles().contains("ROLE_ADMIN")) {
            throw new WsBusinessException("FORBIDDEN", "Admin role required");
        }
        Params p = rawParams == null ? new Params(null) : mapper.convertValue(rawParams, Params.class);
        if (p.notificationId() == null) {
            throw new WsBusinessException("VALIDATION", "notificationId is required");
        }
        boolean ok = apiClient.adminNotificationDelete(p.notificationId());
        if (!ok) {
            throw new WsBusinessException("NOT_FOUND", "Notification not found");
        }
        return Map.of("ok", true);
    }
}
