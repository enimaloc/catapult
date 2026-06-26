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
import java.util.Set;
import java.util.UUID;

/**
 * WebSocket handler for the {@code admin.notification.create} action.
 *
 * <p>Creates a new notification, either targeted at a specific user (when
 * {@code targetUserId} is provided) or broadcast to all active users.</p>
 */
@Component
public class AdminNotificationCreateHandler implements RequestHandler {

    private static final Set<String> VALID_SEVERITIES = Set.of("INFO", "WARNING", "WARN", "ERROR");

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    @Autowired
    public AdminNotificationCreateHandler(ApiClient apiClient) {
        this(apiClient, JsonMapper.builder().build());
    }

    AdminNotificationCreateHandler(ApiClient apiClient, ObjectMapper mapper) {
        this.apiClient = apiClient;
        this.mapper = mapper;
    }

    public record Params(
            UUID targetUserId,
            String title,
            String body,
            String severity,
            String ctaUrl,
            String ctaLabel
    ) {}

    @Override
    public String action() {
        return "admin.notification.create";
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
        Params p = rawParams == null
                ? new Params(null, null, null, null, null, null)
                : mapper.convertValue(rawParams, Params.class);
        if (p.title() == null || p.title().isBlank()) {
            throw new WsBusinessException("VALIDATION", "title is required");
        }
        if (p.title().length() > 200) {
            throw new WsBusinessException("VALIDATION", "title must not exceed 200 characters");
        }
        if (p.body() == null || p.body().isBlank()) {
            throw new WsBusinessException("VALIDATION", "body is required");
        }
        if (p.body().length() > 4000) {
            throw new WsBusinessException("VALIDATION", "body must not exceed 4000 characters");
        }
        if (p.severity() == null || p.severity().isBlank()) {
            throw new WsBusinessException("VALIDATION", "severity is required");
        }
        if (!VALID_SEVERITIES.contains(p.severity())) {
            throw new WsBusinessException("VALIDATION", "severity must be one of INFO, WARN, WARNING, ERROR");
        }
        if (p.ctaUrl() != null && p.ctaUrl().length() > 500) {
            throw new WsBusinessException("VALIDATION", "ctaUrl must not exceed 500 characters");
        }
        if (p.ctaLabel() != null && p.ctaLabel().length() > 80) {
            throw new WsBusinessException("VALIDATION", "ctaLabel must not exceed 80 characters");
        }
        Map<String, Object> result = apiClient.adminNotificationCreate(p);
        if (result == null) {
            throw new WsBusinessException("INTERNAL", "Upstream API call failed");
        }
        return result;
    }
}
