package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Admin-only IGDB game search over WebSocket. Replaces the dead HTTP endpoint
 * {@code /admin/members/igdb/search} that the members admin page pointed at.
 * Calls {@code GET /api/admin/igdb/search} via {@link ApiClient} so auth and
 * caching stay in the API.
 *
 * <p>Params: {@code { "q": "skyr", "limit": 10 }}.</p>
 */
@Component
public class SearchGameHandler implements RequestHandler {

    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_QUERY_LEN = 200;

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    @Autowired
    public SearchGameHandler(ApiClient apiClient) {
        this(apiClient, JsonMapper.builder().build());
    }

    // Visible for tests so they can inject a deterministic ObjectMapper.
    SearchGameHandler(ApiClient apiClient, ObjectMapper mapper) {
        this.apiClient = apiClient;
        this.mapper = mapper;
    }

    public record Params(String q, Integer limit) {
    }

    @Override
    public String action() {
        return "search.game";
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
        Params p = toParams(rawParams);
        if (p == null || p.q() == null || p.q().isBlank()) {
            return List.of();
        }
        if (p.q().length() > MAX_QUERY_LEN) {
            throw new WsBusinessException("INVALID_PARAMS", "q too long");
        }
        int limit = p.limit() == null ? DEFAULT_LIMIT : Math.min(Math.max(p.limit(), 1), MAX_LIMIT);
        List<Map<String, Object>> result = apiClient.get(
                "/api/admin/igdb/search?q={q}",
                new ParameterizedTypeReference<>() {},
                p.q());
        if (result == null) return List.of();
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    private Params toParams(Object raw) {
        if (raw == null) return null;
        return mapper.convertValue(raw, Params.class);
    }
}
