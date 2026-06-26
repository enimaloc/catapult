package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * WebSocket handler for the {@code notification.markAll} command.
 *
 * <p>Marks all notifications as read for the authenticated user.
 * No response payload is returned.</p>
 */
@Component
public class NotificationMarkAllHandler implements RequestHandler {

    private final ApiClient apiClient;

    @Autowired
    public NotificationMarkAllHandler(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String action() {
        return "notification.markAll";
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
        apiClient.notificationMarkAll(userId);
        return null;
    }
}
