package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;

import static org.mockito.Mockito.when;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({WebSecurityConfig.class, InteractionMetrics.class, SimpleMeterRegistry.class})
class AuthControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean SseStreams sse;
    @Autowired MeterRegistry registry;

    private double logins(String outcome) {
        var counter = registry.find("catapult.web.logins").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void callback_storesTheJwtInTheSessionAndOpensTheMembersOwnChannel() throws Exception {
        double succeeded = logins("success");
        when(apiService.exchangeCode("code-1")).thenReturn(new TokenResponse("the-jwt"));
        when(apiService.channelList()).thenReturn(new ChannelListResponse("2", List.of(
                channel("1", "moderated"), channel("2", "own"))));

        mvc.perform(get("/auth/callback").param("code", "code-1").param("state", "s1").session(started(new MockHttpSession(), "s1")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/channel/own"))
                .andExpect(request().sessionAttribute(ApiClient.SESSION_JWT_KEY, "the-jwt"));
        assertThat(logins("success")).isEqualTo(succeeded + 1);
    }

    /** A fresh login ends an impersonation: "Back to admin" must not bring the older admin session back. */
    @Test
    void callback_whileImpersonating_dropsTheAdminsOriginalJwt() throws Exception {
        when(apiService.exchangeCode("code-1")).thenReturn(new TokenResponse("the-jwt"));
        when(apiService.channelList()).thenReturn(new ChannelListResponse("2", List.of(channel("2", "own"))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "member-jwt");
        session.setAttribute(ImpersonationController.ORIGINAL_JWT_KEY, "admin-jwt");

        mvc.perform(get("/auth/callback").param("code", "code-1").param("state", "s1").session(started(session, "s1")))
                .andExpect(redirectedUrl("/channel/own"))
                .andExpect(request().sessionAttribute(ApiClient.SESSION_JWT_KEY, "the-jwt"))
                .andExpect(request().sessionAttributeDoesNotExist(ImpersonationController.ORIGINAL_JWT_KEY));
    }

    /** A session id known before the login is worth nothing after it; the former login's streams close everywhere. */
    @Test
    void callback_givesTheSessionANewId_andClosesTheFormerLoginsStreams() throws Exception {
        when(apiService.exchangeCode("code-1")).thenReturn(new TokenResponse("the-jwt"));
        when(apiService.channelList()).thenReturn(new ChannelListResponse("2", List.of(channel("2", "own"))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "former-jwt");
        SessionChannels.remember(session, List.of(channel("1", "former")));
        String formerId = session.getId();

        mvc.perform(get("/auth/callback").param("code", "code-1").param("state", "s1").session(started(session, "s1")))
                .andExpect(redirectedUrl("/channel/own"));

        assertThat(session.getId()).isNotEqualTo(formerId);
        assertThat(SessionChannels.of(session)).isEmpty();
        org.mockito.Mockito.verify(sse).closeSession(formerId);
    }

    @Test
    void callback_failedExchange_goesBackToLoginWithAnError() throws Exception {
        double failed = logins("failure");
        when(apiService.exchangeCode("expired")).thenReturn(null);

        mvc.perform(get("/auth/callback").param("code", "expired").param("state", "s1").session(started(new MockHttpSession(), "s1")))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(request().sessionAttributeDoesNotExist(ApiClient.SESSION_JWT_KEY));
        assertThat(logins("failure")).isEqualTo(failed + 1);
    }

    /** {@code session}, as /auth/login leaves it: a login started with {@code state}, a moment ago. */
    private static MockHttpSession started(MockHttpSession session, String state) {
        session.setAttribute(AuthController.STATE_KEY, state + ":" + java.time.Instant.now().toEpochMilli());
        return session;
    }

    @Test
    void login_keepsAStateInTheSession_andSendsItWithTheLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();

        String target = mvc.perform(get("/auth/login").param("invite", "ABC234").session(session))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();

        String kept = (String) session.getAttribute(AuthController.STATE_KEY);
        String state = kept.substring(0, kept.lastIndexOf(':'));
        assertThat(target).isEqualTo("/oauth2/authorization/twitch?web_state=" + state + "&invite=ABC234");
        assertThat(state).hasSizeGreaterThanOrEqualTo(43);
    }

    /** Login CSRF: a code the attacker got for their own account cannot log another browser into it. */
    @Test
    void callback_forNoLoginThisSessionStarted_isRefused_beforeTheCodeIsUsed() throws Exception {
        mvc.perform(get("/auth/callback").param("code", "attackers-code"))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(get("/auth/callback").param("code", "attackers-code").param("state", "other")
                        .session(started(new MockHttpSession(), "s1")))
                .andExpect(redirectedUrl("/login?error"));
        MockHttpSession stale = new MockHttpSession();
        stale.setAttribute(AuthController.STATE_KEY, "s1:" + java.time.Instant.now().minus(AuthController.STATE_TTL).minusSeconds(1).toEpochMilli());
        mvc.perform(get("/auth/callback").param("code", "attackers-code").param("state", "s1").session(stale))
                .andExpect(redirectedUrl("/login?error"));

        org.mockito.Mockito.verify(apiService, org.mockito.Mockito.never()).exchangeCode(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void callback_usesTheStateUp() throws Exception {
        when(apiService.exchangeCode("code-1")).thenReturn(new TokenResponse("the-jwt"));
        when(apiService.channelList()).thenReturn(new ChannelListResponse("2", List.of(channel("2", "own"))));
        MockHttpSession session = started(new MockHttpSession(), "s1");

        mvc.perform(get("/auth/callback").param("code", "code-1").param("state", "s1").session(session))
                .andExpect(redirectedUrl("/channel/own"));

        assertThat(session.getAttribute(AuthController.STATE_KEY)).isNull();
    }

    @Test
    void mockLoginPage_doesNotExistOutsideTheMockProfile() throws Exception {
        mvc.perform(get("/oauth2/authorization/twitch")).andExpect(status().isNotFound());
    }

    @Test
    void home_isTheFirstChannel_whenTheMembersOwnIsNotListed() {
        assertThat(AuthController.home(new ChannelListResponse("9", List.of(channel("1", "a"), channel("2", "b")))))
                .isEqualTo("/channel/a");
    }

    @Test
    void home_isTheLanding_withoutAnyChannel() {
        assertThat(AuthController.home(new ChannelListResponse("9", List.of()))).isEqualTo("/");
        assertThat(AuthController.home(null)).isEqualTo("/");
    }

    private static ChannelDto channel(String twitchId, String username) {
        return new ChannelDto(UUID.randomUUID(), twitchId, username, "img", false);
    }
}
