package fr.enimaloc.catapult.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The paths catapult-web relays to catapult-api are the external routes, which are frozen: a path added or dropped here
 * changes what outside callers reach. No later change of the API's routes may touch this list. Swagger UI is no longer
 * relayed: catapult-web serves its own at /docs/api, where catapult-api's addresses redirect (ApiDocsController).
 */
class ApiRelayPathsTest {

    private static final String[] FROZEN = {
        "/oauth2/**",
        "/login/oauth2/**",
        "/api/connect/steam/callback",
        "/api/game/**",
        "/api/user/**",
        "/api/v3/api-docs",
        "/api/v3/api-docs/**",
        "/api/v2/custom-bindings",
        "/api/v2/custom-bindings/**",
    };

    @Test
    void relaysExactlyTheFrozenPaths() {
        assertThat(ApiRelayController.PATHS).containsExactly(FROZEN);
    }

    @Test
    void mappingRepeatsThePaths() throws NoSuchMethodException {
        Method relay = ApiRelayController.class.getMethod(
                "relay", jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class);

        assertThat(relay.getAnnotation(RequestMapping.class).value()).containsExactly(FROZEN);
    }
}
