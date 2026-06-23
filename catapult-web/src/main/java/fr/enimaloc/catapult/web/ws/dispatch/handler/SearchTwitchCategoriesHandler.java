package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.RequestHandler;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * WebSocket replacement for {@code GET /channels/{username}/api/games/search}.
 * Calls the same upstream endpoint via {@link ApiClient} so a single
 * caching/auth path lives in the API.
 *
 * <p>Params accepted from the client:</p>
 * <pre>
 * { "channelId": "tw-username", "q": "skyr", "limit": 8 }
 * </pre>
 *
 * <p>The handler is intentionally {@link #requiresAuth() optional auth}; the
 * downstream API enforces its own auth/access checks (anonymous calls return
 * 401/403 from the API and we surface that as an empty result).</p>
 */
@Component
@RequiredArgsConstructor
public class SearchTwitchCategoriesHandler implements RequestHandler {

    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_QUERY_LEN = 200;

    private final ApiClient apiClient;
    private final ObjectMapper mapper;

    // Spring won't always have a global ObjectMapper before our codec module is loaded;
    // fall back to a fresh JsonMapper so the bean is always constructible.
    public SearchTwitchCategoriesHandler(ApiClient apiClient) {
        this(apiClient, JsonMapper.builder().build());
    }

    public record Params(String channelId, String q, Integer limit) {
    }

    @Override
    public String action() {
        return "search.twitch.categories";
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
        Params p = toParams(rawParams);
        if (p == null || p.q() == null || p.q().isBlank()) {
            return List.of();
        }
        if (p.q().length() > MAX_QUERY_LEN) {
            throw new WsBusinessException("INVALID_PARAMS", "q too long");
        }
        if (p.channelId() == null || p.channelId().isBlank()) {
            throw new WsBusinessException("INVALID_PARAMS", "channelId is required");
        }
        int limit = p.limit() == null ? DEFAULT_LIMIT : Math.min(Math.max(p.limit(), 1), MAX_LIMIT);
        List<Map<String, Object>> result = apiClient.get(
                "/api/channels/{channelId}/games/search?q={q}",
                new ParameterizedTypeReference<>() {},
                p.channelId(), p.q());
        if (result == null) return List.of();
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    private Params toParams(Object raw) {
        if (raw == null) return null;
        return mapper.convertValue(raw, Params.class);
    }
}
