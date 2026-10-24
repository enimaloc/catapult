package fr.enimaloc.catapult.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class FrozenRoutesTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/game/x",
                "/api/user/summary/x",
                "/api/v1/anything",
                "/api/v2/other",
                "/api/connect/steam/callback",
                "/api/v3/api-docs",
                "/api/v3/api-docs/x",
                "/api/twitchat/widget/x/access",
                "/api/twitchat/actions/x",
                "/oauth2/authorization/twitch",
                "/swagger-ui.html",
                "/error"
            })
    void frozen(String path) {
        assertThat(FrozenRoutes.isFrozen(new MockHttpServletRequest("GET", path))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v2/custom-bindings",
                "/api/v2/custom-bindings/x/activate",
                "/api/connect/steam/start",
                "/api/admin/flags",
                "/api/channels/x/settings",
                "/api/gamex"
            })
    void internal(String path) {
        assertThat(FrozenRoutes.isFrozen(new MockHttpServletRequest("GET", path))).isFalse();
    }

    /**
     * Unrouted paths (no handler answers them, so they are a 404 either way): they are under {@code /api/} and match
     * no frozen pattern (a trailing slash matches no pattern), so they answer the problem.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/", "/api/connect/steam/callback/"})
    void unroutedEdgesAreInternal(String path) {
        assertThat(FrozenRoutes.isFrozen(new MockHttpServletRequest("GET", path))).isFalse();
    }
}
