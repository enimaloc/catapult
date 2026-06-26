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
 * WebSocket handler for the {@code notification.list} action.
 *
 * <p>Returns a page of notifications for the authenticated user. The page size
 * is capped at {@value #MAX_SIZE} to prevent abuse.</p>
 */
@Component
public class NotificationListHandler implements RequestHandler {

    static final int MAX_SIZE = 50;

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    @Autowired
    public NotificationListHandler(ApiClient apiClient) {
        this(apiClient, JsonMapper.builder().build());
    }

    NotificationListHandler(ApiClient apiClient, ObjectMapper mapper) {
        this.apiClient = apiClient;
        this.mapper = mapper;
    }

    public record Params(Integer page, Integer size) {}

    @Override
    public String action() {
        return "notification.list";
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
    public Object handle(WsSession session, Object params) {
        UUID userId = session.userId().orElseThrow(
                () -> new WsBusinessException("UNAUTHENTICATED", "Authentication required"));
        Params p = params == null ? new Params(null, null) : mapper.convertValue(params, Params.class);
        int page = p.page() == null ? 0 : Math.max(0, p.page());
        int size = p.size() == null ? 10 : Math.min(MAX_SIZE, Math.max(1, p.size()));
        return apiClient.notificationList(userId, page, size);
    }
}
