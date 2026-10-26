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
 * the frozen ones: a route added under these prefixes is a new external contract, one dropped breaks a caller.
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
            "GET /api/v2/custom-bindings",
            "POST /api/v2/custom-bindings/{idOrName}/activate",
            "POST /api/v2/custom-bindings/deactivate",
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

    private static final String[] PREFIXES = {
        "/api/game/", "/api/user/", "/api/v1/", "/api/v2/", "/api/twitchat/widget/", "/api/twitchat/actions/"
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

        assertThat(external).containsExactlyInAnyOrderElementsOf(FROZEN);
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
