package fr.enimaloc.catapult.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;

class Oauth2LoginFailureHandlerTest {

    private final Oauth2LoginFailureHandler handler = new Oauth2LoginFailureHandler();

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(handler, "webUrl", "http://localhost:8081");
        request = new MockHttpServletRequest("GET", "/login/oauth2/code/twitch");
        response = new MockHttpServletResponse();
    }

    @Test
    void twitchTransientFailure_firstAttempt_retriesInsteadOfShowingError() throws Exception {
        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("server_error")));

        assertThat(response.getRedirectedUrl()).isEqualTo("/oauth2/authorization/twitch");
        assertThat(response.getCookie("catapult_oauth2_retry")).isNotNull();
        assertThat(response.getCookie("catapult_oauth2_retry").getMaxAge()).isEqualTo(60);
    }

    @Test
    void twitchTransientFailure_secondAttempt_showsError() throws Exception {
        request.setCookies(new Cookie("catapult_oauth2_retry", "1"));

        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("server_error")));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8081/login?error");
        assertThat(response.getCookie("catapult_oauth2_retry").getMaxAge()).isZero();
    }

    @Test
    void twitchBusinessError_neverRetried() throws Exception {
        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("not_whitelisted")));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8081/login?error=not_whitelisted");
    }

    @Test
    void secondaryProviderFailure_neverRetried() throws Exception {
        request = new MockHttpServletRequest("GET", "/login/oauth2/code/xbox");

        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("server_error")));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8081/login?error");
    }
}
