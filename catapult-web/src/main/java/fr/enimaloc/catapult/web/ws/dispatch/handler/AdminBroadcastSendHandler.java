package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * WebSocket handler for the {@code admin.broadcast.send} action.
 *
 * <p>Validates the channel and name, then proxies the request to the
 * catapult-api {@code POST /api/admin/broadcast} endpoint.</p>
 */
@Component
public class AdminBroadcastSendHandler implements RequestHandler {

    private static final Set<String> VALID_CHANNELS = Set.of("events.global", "events.admin");

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    @Autowired
    public AdminBroadcastSendHandler(ApiClient apiClient) {
        this(apiClient, JsonMapper.builder().build());
    }

    AdminBroadcastSendHandler(ApiClient apiClient, ObjectMapper mapper) {
        this.apiClient = apiClient;
        this.mapper = mapper;
    }

    public record Params(
            String channel,
            String name,
            Map<String, Object> data
    ) {}

    @Override
    public String action() {
        return "admin.broadcast.send";
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
                ? new Params(null, null, null)
                : mapper.convertValue(rawParams, Params.class);
        if (p.channel() == null || !VALID_CHANNELS.contains(p.channel())) {
            throw new WsBusinessException("VALIDATION", "channel must be one of events.global, events.admin");
        }
        if (p.name() == null || p.name().isBlank()) {
            throw new WsBusinessException("VALIDATION", "name is required");
        }
        Map<String, Object> body = new HashMap<>();
        if (p.data() != null) body.putAll(p.data());
        body.put("name", p.name());
        Map<String, Object> result = apiClient.adminBroadcastSend(body);
        if (result == null) {
            throw new WsBusinessException("INTERNAL", "Upstream API call failed");
        }
        return result;
    }
}
