package fr.enimaloc.catapult.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.util.ReflectionTestUtils;

import fr.enimaloc.catapult.domain.account.UserAccount;
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

    @Test
    void epicErrors_areNotRetried() throws Exception {
        // Even on the retrying (Twitch) callback: these are business errors, a fresh attempt changes nothing.
        for (String code : new String[]{"epic_unavailable", "epic_full", "epic_already_linked"}) {
            response = new MockHttpServletResponse();
            handler.onAuthenticationFailure(request, response, new OAuth2AuthenticationException(new OAuth2Error(code)));

            assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8081/login?error");
            assertThat(response.getCookie("catapult_oauth2_retry").getMaxAge()).isZero();
        }
    }

    @Test
    void epicLinkRefusals_goBackToTheChannelsIntegrationsTab_withTheirCode() throws Exception {
        for (String code : new String[]{"epic_unavailable", "epic_full", "epic_already_linked"}) {
            request = epicCallbackOf("jeb_");
            response = new MockHttpServletResponse();

            handler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException(new OAuth2Error(code)));

            assertThat(response.getRedirectedUrl())
                    .isEqualTo("http://localhost:8081/channel/jeb_?error=" + code + "#integrations");
        }
    }

    @Test
    void epicLinkRefusal_withoutTheMembersSession_showsTheLoginError() throws Exception {
        request = new MockHttpServletRequest("GET", "/login/oauth2/code/epic");

        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("epic_full")));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8081/login?error");
    }

    @Test
    void otherLinkFailures_stillShowTheLoginError() throws Exception {
        for (String code : new String[]{"battlenet_full", "battlenet_unavailable", "server_error"}) {
            request = epicCallbackOf("jeb_");
            response = new MockHttpServletResponse();

            handler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException(new OAuth2Error(code)));

            assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8081/login?error");
        }
    }

    /** The Epic callback of a member logged in through Twitch as {@code login}, as the link's session holds them. */
    private static MockHttpServletRequest epicCallbackOf(String login) {
        return callbackOf("epic", login);
    }

    private static MockHttpServletRequest callbackOf(String registrationId, String login) {
        UserAccount account = new UserAccount();
        account.setTwitchUsername(login);
        CatapultOAuth2User user = new CatapultOAuth2User(null, account, false);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities())));
        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/" + registrationId);
        callback.setSession(session);
        return callback;
    }

    @Test
    void twitchRetry_keepsCatapultWebsState_butGivingUpForgetsIt() throws Exception {
        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("server_error")));
        assertThat(response.getCookie(InviteCodeRelayFilter.WEB_STATE_COOKIE_NAME)).isNull();

        MockHttpServletResponse givenUp = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, givenUp,
                new OAuth2AuthenticationException(new OAuth2Error("not_whitelisted")));
        assertThat(givenUp.getCookie(InviteCodeRelayFilter.WEB_STATE_COOKIE_NAME).getMaxAge()).isZero();
    }

    @Test
    void wowImportRefusals_goBackToTheChannelsIntegrationsTab_withTheirCode() throws Exception {
        for (String code : new String[]{"battlenet_stats_unavailable", "battlenet_stats_failed",
                "battlenet_stats_mismatch"}) {
            request = callbackOf("battlenet-stats", "jeb_");
            response = new MockHttpServletResponse();

            handler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException(new OAuth2Error(code)));

            assertThat(response.getRedirectedUrl())
                    .isEqualTo("http://localhost:8081/channel/jeb_?error=" + code + "#api");
            assertThat(response.getCookie("catapult_oauth2_retry").getMaxAge()).isZero();
        }
    }

    @Test
    void anyOtherWowImportFailure_isAFailedImportOnTheCard() throws Exception {
        // A failed token exchange or an unexpected error: never the login page.
        for (String code : new String[]{"invalid_token_response", "server_error"}) {
            request = callbackOf("battlenet-stats", "jeb_");
            response = new MockHttpServletResponse();

            handler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException(new OAuth2Error(code)));

            assertThat(response.getRedirectedUrl())
                    .isEqualTo("http://localhost:8081/channel/jeb_?error=battlenet_stats_failed#api");
        }
    }

    @Test
    void decliningTheBlizzardConsent_isACancelledImportNotAFailure() throws Exception {
        request = callbackOf("battlenet-stats", "jeb_");

        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:8081/channel/jeb_?error=battlenet_stats_cancelled#api");
    }

    @Test
    void wowImportWithoutTheMembersSession_showsTheLoginError() throws Exception {
        request = new MockHttpServletRequest("GET", "/login/oauth2/code/battlenet-stats");

        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("unauthorized")));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8081/login?error");
    }

    @Test
    void bungieRefusals_goBackToTheCard_andAnyOtherFailureIsAFailedLink() throws Exception {
        for (String code : new String[]{"bungie_unavailable", "bungie_mismatch", "bungie_failed"}) {
            request = callbackOf("bungie", "jeb_");
            response = new MockHttpServletResponse();
            handler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException(new OAuth2Error(code)));
            assertThat(response.getRedirectedUrl())
                    .isEqualTo("http://localhost:8081/channel/jeb_?error=" + code + "#api");
        }
        request = callbackOf("bungie", "jeb_");
        response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token_response")));
        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:8081/channel/jeb_?error=bungie_failed#api");

        request = callbackOf("bungie", "jeb_");
        response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));
        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:8081/channel/jeb_?error=bungie_cancelled#api");
    }

    @Test
    void osuRefusals_goBackToTheCard_andAnyOtherFailureIsAFailedLink() throws Exception {
        for (String code : new String[]{"osu_unavailable", "osu_mismatch", "osu_failed"}) {
            request = callbackOf("osu", "jeb_");
            response = new MockHttpServletResponse();
            handler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException(new OAuth2Error(code)));
            assertThat(response.getRedirectedUrl())
                    .isEqualTo("http://localhost:8081/channel/jeb_?error=" + code + "#api");
        }
        request = callbackOf("osu", "jeb_");
        response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token_response")));
        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:8081/channel/jeb_?error=osu_failed#api");

        request = callbackOf("osu", "jeb_");
        response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));
        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:8081/channel/jeb_?error=osu_cancelled#api");
    }

    @Test
    void guestStarRefusals_goBackToTheSettings_andAnyOtherFailureIsAFailedConsent() throws Exception {
        String[][] cases = {{"guest_star_mismatch", "guest_star_mismatch"}, {"access_denied", "guest_star_cancelled"},
                {"invalid_token_response", "guest_star_failed"}};
        for (String[] c : cases) {
            request = callbackOf("twitch-guest-star", "jeb_");
            response = new MockHttpServletResponse();
            handler.onAuthenticationFailure(request, response,
                    new OAuth2AuthenticationException(new OAuth2Error(c[0])));
            assertThat(response.getRedirectedUrl())
                    .isEqualTo("http://localhost:8081/channel/jeb_?error=" + c[1] + "#settings");
        }
    }

    @Test
    void aGuestStarFailureWithoutCode_isAFailedConsent() throws Exception {
        request = callbackOf("twitch-guest-star", "jeb_");
        response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, response,
                new org.springframework.security.authentication.BadCredentialsException("boom"));
        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:8081/channel/jeb_?error=guest_star_failed#settings");
    }
}
