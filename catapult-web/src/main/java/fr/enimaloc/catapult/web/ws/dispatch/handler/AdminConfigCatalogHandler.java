package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.service.config.WebConfigCatalogService;
import fr.enimaloc.catapult.web.service.config.WebConfigEntry;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket handler for the {@code admin.config.catalog} action.
 *
 * <p>Returns the config catalog for the requested module. The {@code web} module
 * is served from the local {@link WebConfigCatalogService}; all other modules
 * are proxied to catapult-api.</p>
 */
@Component
public class AdminConfigCatalogHandler implements RequestHandler {

    private static final String MODULE_WEB = "web";
    private static final String MODULE_API = "api";

    private final ApiClient apiClient;
    private final WebConfigCatalogService webConfigCatalogService;
    private final ObjectMapper mapper;

    @Autowired
    public AdminConfigCatalogHandler(ApiClient apiClient, WebConfigCatalogService webConfigCatalogService) {
        this(apiClient, webConfigCatalogService, JsonMapper.builder().build());
    }

    AdminConfigCatalogHandler(ApiClient apiClient, WebConfigCatalogService webConfigCatalogService, ObjectMapper mapper) {
        this.apiClient = apiClient;
        this.webConfigCatalogService = webConfigCatalogService;
        this.mapper = mapper;
    }

    public record Params(String module) {}

    @Override
    public String action() {
        return "admin.config.catalog";
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
        String module = (p.module() == null || p.module().isBlank()) ? MODULE_API : p.module();

        if (MODULE_WEB.equals(module)) {
            return webConfigCatalogService.catalog().stream()
                    .map(AdminConfigCatalogHandler::toMap)
                    .toList();
        }

        List<Map<String, Object>> entries = apiClient.adminConfigCatalog(module);
        if (entries == null) {
            throw new WsBusinessException("INTERNAL", "Upstream API call failed");
        }
        return entries;
    }

    private static Map<String, Object> toMap(WebConfigEntry e) {
        Map<String, Object> m = new HashMap<>();
        m.put("key", e.key());
        m.put("value", e.value());
        m.put("secret", e.secret());
        m.put("restartRequired", e.restartRequired());
        m.put("overridden", e.overridden());
        m.put("taboo", e.taboo());
        m.put("source", e.source());
        return m;
    }
}
