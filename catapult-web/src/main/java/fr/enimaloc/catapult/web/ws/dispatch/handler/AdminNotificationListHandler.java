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

/**
 * WebSocket handler for the {@code admin.notification.list} action.
 *
 * <p>Returns a page of notifications for site administrators. The page size
 * is capped at {@value #MAX_SIZE} to prevent abuse.</p>
 */
@Component
public class AdminNotificationListHandler implements RequestHandler {

    static final int MAX_SIZE = 50;

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    @Autowired
    public AdminNotificationListHandler(ApiClient apiClient) {
        this(apiClient, JsonMapper.builder().build());
    }

    AdminNotificationListHandler(ApiClient apiClient, ObjectMapper mapper) {
        this.apiClient = apiClient;
        this.mapper = mapper;
    }

    public record Params(Integer page, Integer size) {}

    @Override
    public String action() {
        return "admin.notification.list";
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
        Params p = rawParams == null ? new Params(null, null) : mapper.convertValue(rawParams, Params.class);
        int page = p.page() == null ? 0 : Math.max(0, p.page());
        int size = p.size() == null ? 20 : Math.min(MAX_SIZE, Math.max(1, p.size()));
        Map<String, Object> result = apiClient.adminNotificationList(page, size);
        if (result == null) {
            throw new WsBusinessException("INTERNAL", "Upstream API call failed");
        }
        return result;
    }
}
