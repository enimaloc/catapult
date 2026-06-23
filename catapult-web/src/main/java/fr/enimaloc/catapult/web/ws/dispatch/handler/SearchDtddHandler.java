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
 * WebSocket replacement for {@code GET /api/channel/dtdd-mapping/search}.
 *
 * <p>The DtDD endpoint is global (not channel-scoped) — it queries the central
 * mapping cache plus the upstream DtDD API. {@link #handle(WsSession, Object)}
 * returns the raw search-response shape produced by the API
 * ({@code { "results": [ { dtddId, name, mediaType, posterUrl }, ... ] }}).</p>
 */
@Component
public class SearchDtddHandler implements RequestHandler {

    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_QUERY_LEN = 200;

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    @Autowired
    public SearchDtddHandler(ApiClient apiClient) {
        this(apiClient, JsonMapper.builder().build());
    }

    SearchDtddHandler(ApiClient apiClient, ObjectMapper mapper) {
        this.apiClient = apiClient;
        this.mapper = mapper;
    }

    public record Params(String q, Integer limit) {
    }

    @Override
    public String action() {
        return "search.dtdd";
    }

    @Override
    public boolean requiresAuth() {
        return false;
    }

    @Override
    public boolean requiresAdmin() {
        return false;
    }

    @Override
    public Object handle(WsSession session, Object rawParams) {
        Params p = rawParams == null ? null : mapper.convertValue(rawParams, Params.class);
        if (p == null || p.q() == null || p.q().isBlank()) {
            return Map.of("results", List.of());
        }
        if (p.q().length() > MAX_QUERY_LEN) {
            throw new WsBusinessException("INVALID_PARAMS", "q too long");
        }
        int limit = p.limit() == null ? DEFAULT_LIMIT : Math.min(Math.max(p.limit(), 1), MAX_LIMIT);

        Map<String, Object> body = apiClient.get(
                "/api/channel/dtdd-mapping/search?q={q}",
                new ParameterizedTypeReference<>() {},
                p.q());
        if (body == null) return Map.of("results", List.of());
        Object results = body.get("results");
        if (!(results instanceof List<?> list)) return Map.of("results", List.of());
        return Map.of("results", list.size() > limit ? list.subList(0, limit) : list);
    }
}
