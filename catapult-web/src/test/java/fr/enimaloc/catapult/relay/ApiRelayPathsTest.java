package fr.enimaloc.catapult.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The paths catapult-web relays to catapult-api are the external routes: a path added or dropped here changes what
 * outside callers reach. The public API by resource ({@code /api/me/**}, {@code /api/games/**}) replaced
 * {@code /api/v2/custom-bindings}; the legacy {@code /api/game/**} and {@code /api/user/**} stay relayed (catapult-api
 * gates them itself). Swagger UI is no longer
 * relayed: catapult-web serves its own at /docs/api, where catapult-api's addresses redirect (ApiDocsController).
 */
class ApiRelayPathsTest {

    private static final String[] FROZEN = {
        "/oauth2/**",
        "/login/oauth2/**",
        "/api/connect/steam/callback",
        "/api/game/**",
        "/api/user/**",
        "/api/me/game",
        "/api/me/game/**",
        "/api/me/bindings/**",
        "/api/me/custom-bindings",
        "/api/me/custom-bindings/**",
        "/api/games/**",
        "/api/v3/api-docs",
        "/api/v3/api-docs/**",
    };

    @Test
    void relay_forwardsNewPublicRoutes_andNotV2CustomBindings() {
        assertThat(ApiRelayController.PATHS).containsExactly(FROZEN);
        assertThat(ApiRelayController.PATHS).noneMatch(path -> path.startsWith("/api/v2/"));
    }

    @Test
    void mappingRepeatsThePaths() throws NoSuchMethodException {
        Method relay = ApiRelayController.class.getMethod(
                "relay", jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class);

        assertThat(relay.getAnnotation(RequestMapping.class).value()).containsExactly(FROZEN);
    }
}
