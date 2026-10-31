package fr.enimaloc.catapult.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.FullContextContainers;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The handler routes outside callers use (OBS widgets, Twitchat, the API token clients, Steam's return) are exactly
 * the frozen ones and the public API's: a route added under these prefixes is a new external contract, one dropped
 * breaks a caller.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.config.additional-location=file:./src/main/resources/")
@TestPropertySource(locations = "classpath:full-context.properties")
@Import(FullContextContainers.class)
@DirtiesContext
class ExternalRouteSetTest {

    private static final Set<String> FROZEN = Set.of(
            "GET /api/connect/steam/callback",
            "GET /api/game/{uuid}",
            "GET /api/user/game/{uuid}",
            "GET /api/user/summary/{uuid}",
            "GET /api/user/igdb/{igdbId}",
            "GET /api/user/steam/{appId}",
            "GET /api/user/xbox/{productId}",
            "GET /api/user/dtdd/{dtddId}",
            "GET /api/user/catapult/{bindingId}",
            "GET /api/twitchat/widget/{uuid}/access",
            "GET /api/twitchat/widget/{uuid}",
            "POST /api/twitchat/widget/{uuid}/notifications/flush",
            "POST /api/twitchat/actions/{token}");

    /** The public API, versioned: by the personal API token under /api/me, open under /api/games. */
    private static final Set<String> PUBLIC = Set.of(
            "GET /api/me/game",
            "GET /api/me/game/summary",
            "GET /api/me/bindings/{bindingId}",
            "GET /api/me/custom-bindings",
            "PUT /api/me/custom-bindings/active",
            "DELETE /api/me/custom-bindings/active",
            "GET /api/games/igdb/{igdbId}",
            "GET /api/games/steam/{appId}",
            "GET /api/games/xbox/{productId}",
            "GET /api/games/dtdd/{dtddId}");

    private static final String[] PREFIXES = {
        "/api/game/", "/api/user/", "/api/v1/", "/api/v2/", "/api/twitchat/widget/", "/api/twitchat/actions/",
        "/api/me/", "/api/games/"
    };

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void externalRoutesAreExactlyTheFrozenSet() {
        Set<String> external = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of()
                    : info.getPathPatternsCondition().getPatternValues();
            for (String pattern : patterns) {
                if (!isExternal(pattern)) {
                    continue;
                }
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                if (methods.isEmpty()) {
                    external.add("ANY " + pattern);
                }
                methods.forEach(method -> external.add(method.name() + " " + pattern));
            }
        }

        Set<String> expected = new TreeSet<>(FROZEN);
        expected.addAll(PUBLIC);
        assertThat(external).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static boolean isExternal(String pattern) {
        if (pattern.equals("/api/connect/steam/callback")) {
            return true;
        }
        for (String prefix : PREFIXES) {
            if (pattern.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
