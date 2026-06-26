package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.service.config.WebConfigOverrideService;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * WebSocket handler for the {@code admin.config.reset} action.
 *
 * <p>Resets a config value to its default for the requested module. The
 * {@code web} module is handled by {@link WebConfigOverrideService}; all other
 * modules are proxied to catapult-api via {@link ApiClient#adminConfigReset}.</p>
 */
@Component
public class AdminConfigResetHandler implements RequestHandler {

    private static final String MODULE_WEB = "web";

    private final ApiClient apiClient;
    private final WebConfigOverrideService webConfigOverrideService;
    private final ObjectMapper mapper;

    @Autowired
    public AdminConfigResetHandler(ApiClient apiClient, WebConfigOverrideService webConfigOverrideService) {
        this(apiClient, webConfigOverrideService, JsonMapper.builder().build());
    }

    AdminConfigResetHandler(ApiClient apiClient, WebConfigOverrideService webConfigOverrideService, ObjectMapper mapper) {
        this.apiClient = apiClient;
        this.webConfigOverrideService = webConfigOverrideService;
        this.mapper = mapper;
    }

    public record Params(String module, String key) {}

    @Override
    public String action() {
        return "admin.config.reset";
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
                ? new Params(null, null)
                : mapper.convertValue(rawParams, Params.class);
        if (p.module() == null || p.module().isBlank()) {
            throw new WsBusinessException("VALIDATION", "module is required");
        }
        if (p.key() == null || p.key().isBlank()) {
            throw new WsBusinessException("VALIDATION", "key is required");
        }

        if (MODULE_WEB.equals(p.module())) {
            try {
                webConfigOverrideService.clear(p.key());
            } catch (ResponseStatusException e) {
                throw WsBusinessException.fromResponseStatus(e);
            }
        } else {
            boolean ok = apiClient.adminConfigReset(p.key());
            if (!ok) {
                throw new WsBusinessException("INTERNAL", "Upstream API call failed");
            }
        }
        return Map.of("ok", true);
    }
}
