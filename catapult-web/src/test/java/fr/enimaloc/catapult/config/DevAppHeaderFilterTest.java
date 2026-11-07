package fr.enimaloc.catapult.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DevAppHeaderFilterTest {
    @Test
    void namesTheAppOnEveryResponse_evenOneTheChainRefuses() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain refusing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws java.io.IOException {
                ((HttpServletResponse) res).sendError(403);
            }
        };

        new DevAppHeaderFilter().doFilter(new MockHttpServletRequest("GET", "/anything"), response, refusing);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Blinder-App")).isEqualTo("catapult");
    }

    @Test
    void onlyUnderTheDevProfile() {
        assertThat(DevAppHeaderFilter.class.getAnnotation(Profile.class).value()).containsExactly("dev");
    }
}
