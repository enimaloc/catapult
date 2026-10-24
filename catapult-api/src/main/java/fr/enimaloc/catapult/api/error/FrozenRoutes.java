package fr.enimaloc.catapult.api.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The routes whose error answers are frozen: external callers (OBS, Twitchat, pasted URLs, catapult-web's relay)
 * depend on them as they are, so {@link ProblemAdvice} leaves them to Spring's and Boot's default answers.
 * Everything outside {@code /api/} is frozen, including Boot's {@code /error} (the path of an error dispatch).
 */
public final class FrozenRoutes {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    private static final RequestMatcher API = PATHS.matcher("/api/**");

    /** Under {@code /api/v2/**}, but not released yet: it answers problems. */
    private static final List<RequestMatcher> UNFROZEN =
            List.of(PATHS.matcher("/api/v2/custom-bindings"), PATHS.matcher("/api/v2/custom-bindings/**"));

    private static final List<RequestMatcher> FROZEN = List.of(
            PATHS.matcher("/api/game/**"),
            PATHS.matcher("/api/user/**"),
            PATHS.matcher("/api/v1/**"),
            PATHS.matcher("/api/v2/**"),
            PATHS.matcher("/api/connect/steam/callback"),
            PATHS.matcher("/api/v3/api-docs"),
            PATHS.matcher("/api/v3/api-docs/**"),
            PATHS.matcher("/api/twitchat/widget/**"),
            PATHS.matcher("/api/twitchat/actions/**"));

    private FrozenRoutes() {}

    public static boolean isFrozen(HttpServletRequest request) {
        if (!API.matches(request)) {
            return true;
        }
        if (UNFROZEN.stream().anyMatch(m -> m.matches(request))) {
            return false;
        }
        return FROZEN.stream().anyMatch(m -> m.matches(request));
    }
}
