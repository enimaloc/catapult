package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import jakarta.servlet.http.Cookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwitchLoginSuccessHandlerTest {

    @Mock OAuth2AuthorizedClientRepository authorizedClientRepository;
    @Mock OAuthTokenRepository oAuthTokenRepository;
    @Mock TokenEncryptionService tokenEncryptionService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock JwtService jwtService;
    @Mock AuthCodeStore codeStore;

    @Mock org.springframework.beans.factory.ObjectProvider<fr.enimaloc.catapult.service.bungie.BungieLinkService> bungieLinkProvider;
    @Mock fr.enimaloc.catapult.service.bungie.BungieLinkService bungieLinks;
    @Mock org.springframework.beans.factory.ObjectProvider<fr.enimaloc.catapult.service.osu.OsuLinkService> osuLinkProvider;
    @Mock fr.enimaloc.catapult.service.osu.OsuLinkService osuLinks;
    @InjectMocks TwitchLoginSuccessHandler handler;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private UserAccount userAccount;
    private CatapultOAuth2User catapultUser;

    @BeforeEach
    void setup() {
        // two providers of one raw type: each is set by name, never left to injection by type
        ReflectionTestUtils.setField(handler, "bungieLinks", bungieLinkProvider);
        ReflectionTestUtils.setField(handler, "osuLinks", osuLinkProvider);
        handler.init();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();

        userAccount = new UserAccount();
        userAccount.setId(UUID.randomUUID());
        userAccount.setTwitchId("twitch-123");

        catapultUser = mock(CatapultOAuth2User.class);
        when(catapultUser.getUserAccount()).thenReturn(userAccount);
    }

    @Test
    void nonOAuth2Token_skipsTokenSave() throws Exception {
        Authentication nonOAuth2 = mock(Authentication.class);

        handler.onAuthenticationSuccess(request, response, nonOAuth2);

        verify(oAuthTokenRepository, never()).save(any());
    }

    @Test
    void twitchToken_noClient_skipsTokenSave() throws Exception {
        var token = mock(OAuth2AuthenticationToken.class);
        when(token.getAuthorizedClientRegistrationId()).thenReturn("twitch");
        when(token.getPrincipal()).thenReturn(catapultUser);
        when(authorizedClientRepository.loadAuthorizedClient("twitch", token, request)).thenReturn(null);

        handler.onAuthenticationSuccess(request, response, token);

        verify(oAuthTokenRepository, never()).save(any());
    }

    @Test
    void twitchToken_noRefreshToken_skipsTokenSave() throws Exception {
        var token = mock(OAuth2AuthenticationToken.class);
        when(token.getAuthorizedClientRegistrationId()).thenReturn("twitch");
        when(token.getPrincipal()).thenReturn(catapultUser);

        var client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(null);
        when(authorizedClientRepository.loadAuthorizedClient("twitch", token, request)).thenReturn(client);

        handler.onAuthenticationSuccess(request, response, token);

        verify(oAuthTokenRepository, never()).save(any());
    }

    @Test
    void twitchToken_withRefreshToken_savesToken() throws Exception {
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("twitch");
        when(authToken.getPrincipal()).thenReturn(catapultUser);

        var refreshToken = mock(OAuth2RefreshToken.class);
        when(refreshToken.getTokenValue()).thenReturn("raw-refresh-token");

        var accessToken = mock(OAuth2AccessToken.class);
        when(accessToken.getExpiresAt()).thenReturn(Instant.now().plusSeconds(3600));

        var client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(refreshToken);
        when(client.getAccessToken()).thenReturn(accessToken);
        when(authorizedClientRepository.loadAuthorizedClient("twitch", authToken, request)).thenReturn(client);

        OAuthToken oauthToken = new OAuthToken();
        when(oAuthTokenRepository.findByUserAndProvider(userAccount, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(oauthToken));
        when(tokenEncryptionService.encrypt("raw-refresh-token")).thenReturn("encrypted-token");

        handler.onAuthenticationSuccess(request, response, authToken);

        verify(tokenEncryptionService).encrypt("raw-refresh-token");
        verify(oAuthTokenRepository).save(oauthToken);
    }

    @Test
    void twitchToken_withRefreshToken_noDbRecord_doesNotSave() throws Exception {
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("twitch");
        when(authToken.getPrincipal()).thenReturn(catapultUser);

        var refreshToken = mock(OAuth2RefreshToken.class);
        when(refreshToken.getTokenValue()).thenReturn("raw-refresh-token");

        var accessToken = mock(OAuth2AccessToken.class);
        when(accessToken.getExpiresAt()).thenReturn(null);

        var client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(refreshToken);
        when(client.getAccessToken()).thenReturn(accessToken);
        when(authorizedClientRepository.loadAuthorizedClient("twitch", authToken, request)).thenReturn(client);

        when(oAuthTokenRepository.findByUserAndProvider(userAccount, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());

        handler.onAuthenticationSuccess(request, response, authToken);

        verify(oAuthTokenRepository, never()).save(any());
    }

    @Test
    void nonTwitchOAuth2Token_skipsTokenSave() throws Exception {
        var token = mock(OAuth2AuthenticationToken.class);
        when(token.getAuthorizedClientRegistrationId()).thenReturn("discord");
        when(token.getPrincipal()).thenReturn(catapultUser);

        handler.onAuthenticationSuccess(request, response, token);

        verify(oAuthTokenRepository, never()).save(any());
    }

    @Test
    void xboxToken_withRefreshToken_savesToken() throws Exception {
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("xbox");
        when(authToken.getPrincipal()).thenReturn(catapultUser);

        var refreshToken = mock(OAuth2RefreshToken.class);
        when(refreshToken.getTokenValue()).thenReturn("raw-xbox-refresh");

        var accessToken = mock(OAuth2AccessToken.class);
        when(accessToken.getExpiresAt()).thenReturn(Instant.now().plusSeconds(3600));

        var client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(refreshToken);
        when(client.getAccessToken()).thenReturn(accessToken);
        when(authorizedClientRepository.loadAuthorizedClient("xbox", authToken, request)).thenReturn(client);

        OAuthToken oauthToken = new OAuthToken();
        when(oAuthTokenRepository.findByUserAndProvider(userAccount, OAuthToken.Provider.XBOX))
            .thenReturn(Optional.of(oauthToken));
        when(tokenEncryptionService.encrypt("raw-xbox-refresh")).thenReturn("encrypted-token");

        handler.onAuthenticationSuccess(request, response, authToken);

        verify(tokenEncryptionService).encrypt("raw-xbox-refresh");
        verify(oAuthTokenRepository).save(oauthToken);
    }

    @Test
    void xboxToken_noWebUrlConfigured_fallsBackToDefaultTarget() throws Exception {
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("xbox");
        when(authToken.getPrincipal()).thenReturn(catapultUser);
        when(authorizedClientRepository.loadAuthorizedClient("xbox", authToken, request)).thenReturn(null);

        handler.onAuthenticationSuccess(request, response, authToken);

        assertThat(response.getRedirectedUrl()).isEqualTo("/dashboard");
    }

    @Test
    void xboxToken_withWebUrlConfigured_redirectsToChannelSettings() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        userAccount.setTwitchUsername("jeb_");

        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("xbox");
        when(authToken.getPrincipal()).thenReturn(catapultUser);
        when(authorizedClientRepository.loadAuthorizedClient("xbox", authToken, request)).thenReturn(null);

        handler.onAuthenticationSuccess(request, response, authToken);

        assertThat(response.getRedirectedUrl()).isEqualTo("https://web.example.com/channel/jeb_#integrations");
    }

    @Test
    void battleNetLink_forgetsTheBlizzardTokenAndRedirectsToTheChannel() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        userAccount.setTwitchUsername("jeb_");
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("battlenet");
        when(authToken.getPrincipal()).thenReturn(catapultUser);

        handler.onAuthenticationSuccess(request, response, authToken);

        verify(authorizedClientRepository).removeAuthorizedClient("battlenet", authToken, request, response);
        verify(oAuthTokenRepository, never()).save(any());
        assertThat(response.getRedirectedUrl()).isEqualTo("https://web.example.com/channel/jeb_#integrations");
    }

    @Test
    void userTokenIsForgottenAfterImport() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        userAccount.setTwitchUsername("jeb_");
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("battlenet-stats");
        when(authToken.getPrincipal()).thenReturn(catapultUser);

        handler.onAuthenticationSuccess(request, response, authToken);

        verify(authorizedClientRepository).removeAuthorizedClient("battlenet-stats", authToken, request, response);
        verify(authorizedClientRepository, never()).removeAuthorizedClient(eq("battlenet"), any(), any(), any());
        verify(authorizedClientRepository, never()).loadAuthorizedClient(any(), any(), any());
        verify(oAuthTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://web.example.com/channel/jeb_?imported=wow#api");
    }

    @Test
    void anImportThatFoundNoCharacterIsToldApart() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        userAccount.setTwitchUsername("jeb_");
        request.setAttribute(TwitchLoginSuccessHandler.WOW_IMPORT_COUNT_ATTRIBUTE, 0);
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("battlenet-stats");
        when(authToken.getPrincipal()).thenReturn(catapultUser);

        handler.onAuthenticationSuccess(request, response, authToken);

        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://web.example.com/channel/jeb_?imported=wow-empty#api");
    }

    @Test
    void userTokenIsForgottenEvenWithoutTheWeb() throws Exception {
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("battlenet-stats");
        when(authToken.getPrincipal()).thenReturn(catapultUser);

        handler.onAuthenticationSuccess(request, response, authToken);

        verify(authorizedClientRepository).removeAuthorizedClient("battlenet-stats", authToken, request, response);
        assertThat(response.getRedirectedUrl()).isEqualTo("/dashboard");
    }

    @Test
    void epicAuthorizedClient_isRemovedAfterTheLink() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        userAccount.setTwitchUsername("jeb_");
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("epic");
        when(authToken.getPrincipal()).thenReturn(catapultUser);

        handler.onAuthenticationSuccess(request, response, authToken);

        verify(authorizedClientRepository).removeAuthorizedClient("epic", authToken, request, response);
        verify(oAuthTokenRepository, never()).save(any());
        assertThat(response.getRedirectedUrl()).isEqualTo("https://web.example.com/channel/jeb_#integrations");
    }

    @Test
    void twitchToken_withWebUrl_sendsCatapultWebsStateBackAndForgetsIt() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(InviteCodeRelayFilter.WEB_STATE_SESSION_KEY, "st-123_x");
        request.setSession(session);
        var authToken = twitchLogin();

        handler.onAuthenticationSuccess(request, response, authToken);

        assertThat(response.getRedirectedUrl()).isEqualTo("https://web.example.com/auth/callback?code=one-time&state=st-123_x");
        assertThat(session.getAttribute(InviteCodeRelayFilter.WEB_STATE_SESSION_KEY)).isNull();
        assertThat(response.getCookie(InviteCodeRelayFilter.WEB_STATE_COOKIE_NAME).getMaxAge()).isZero();
    }

    @Test
    void twitchToken_withWebUrl_takesTheStateFromTheCookieAfterARetry() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        request.setCookies(new Cookie(InviteCodeRelayFilter.WEB_STATE_COOKIE_NAME, "from-cookie"));
        var authToken = twitchLogin();

        handler.onAuthenticationSuccess(request, response, authToken);

        assertThat(response.getRedirectedUrl()).isEqualTo("https://web.example.com/auth/callback?code=one-time&state=from-cookie");
    }

    @Test
    void twitchToken_withWebUrl_withoutState_sendsTheCodeAlone() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        var authToken = twitchLogin();

        handler.onAuthenticationSuccess(request, response, authToken);

        assertThat(response.getRedirectedUrl()).isEqualTo("https://web.example.com/auth/callback?code=one-time");
    }

    private OAuth2AuthenticationToken twitchLogin() {
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("twitch");
        when(authToken.getPrincipal()).thenReturn(catapultUser);
        when(authorizedClientRepository.loadAuthorizedClient("twitch", authToken, request)).thenReturn(null);
        when(jwtService.generate(catapultUser)).thenReturn("jwt");
        when(codeStore.issue("jwt")).thenReturn("one-time");
        return authToken;
    }

    private org.springframework.security.oauth2.client.OAuth2AuthorizedClient bungieClient(String refresh) {
        var registration = org.springframework.security.oauth2.client.registration.ClientRegistration
                .withRegistrationId("bungie").clientId("12345").redirectUri("http://localhost/cb")
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationUri("https://www.bungie.net/en/OAuth/Authorize")
                .tokenUri("https://www.bungie.net/platform/app/oauth/token/").build();
        var access = new org.springframework.security.oauth2.core.OAuth2AccessToken(
                org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER, "access",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(3600));
        return new org.springframework.security.oauth2.client.OAuth2AuthorizedClient(registration, "42", access,
                refresh == null ? null : new org.springframework.security.oauth2.core.OAuth2RefreshToken(refresh,
                        java.time.Instant.now()));
    }

    @Test
    void bungieLink_isWrittenWithItsRefreshToken_andSpringsCopyIsForgotten() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        userAccount.setTwitchUsername("jeb_");
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("bungie");
        when(authToken.getPrincipal()).thenReturn(catapultUser);
        when(authorizedClientRepository.loadAuthorizedClient("bungie", authToken, request))
                .thenReturn(bungieClient("refresh-token"));
        when(bungieLinkProvider.getIfAvailable()).thenReturn(bungieLinks);
        var pending = new fr.enimaloc.catapult.service.bungie.BungieLinkService.PendingLink("42", "G#0042", 3, "461",
                java.time.Instant.now());
        request.setAttribute(fr.enimaloc.catapult.service.bungie.BungieLinkService.PENDING_ATTRIBUTE, pending);

        handler.onAuthenticationSuccess(request, response, authToken);

        verify(bungieLinks).complete(eq(userAccount), eq(pending), eq("refresh-token"), eq("access"), any());
        verify(authorizedClientRepository).removeAuthorizedClient("bungie", authToken, request, response);
        verify(oAuthTokenRepository, never()).save(any());
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://web.example.com/channel/jeb_?linked=bungie#api");
    }

    @Test
    void bungieLink_withoutACheckedLoginOrARefreshToken_isAFailedLink_andTheTokenIsStillForgotten() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        userAccount.setTwitchUsername("jeb_");
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("bungie");
        when(authToken.getPrincipal()).thenReturn(catapultUser);
        when(authorizedClientRepository.loadAuthorizedClient("bungie", authToken, request))
                .thenReturn(bungieClient(null));
        when(bungieLinkProvider.getIfAvailable()).thenReturn(bungieLinks);

        handler.onAuthenticationSuccess(request, response, authToken);

        verify(bungieLinks, never()).complete(any(), any(), any(), any(), any());
        verify(authorizedClientRepository).removeAuthorizedClient("bungie", authToken, request, response);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://web.example.com/channel/jeb_?error=bungie_failed#api");
    }

    @Test
    void bungieLink_aFailedWrite_isAFailedLink() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        userAccount.setTwitchUsername("jeb_");
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("bungie");
        when(authToken.getPrincipal()).thenReturn(catapultUser);
        when(authorizedClientRepository.loadAuthorizedClient("bungie", authToken, request))
                .thenReturn(bungieClient("refresh-token"));
        when(bungieLinkProvider.getIfAvailable()).thenReturn(bungieLinks);
        var pending = new fr.enimaloc.catapult.service.bungie.BungieLinkService.PendingLink("42", null, null, null,
                java.time.Instant.now());
        request.setAttribute(fr.enimaloc.catapult.service.bungie.BungieLinkService.PENDING_ATTRIBUTE, pending);
        org.mockito.Mockito.doThrow(new IllegalStateException("refresh-token")).when(bungieLinks)
                .complete(any(), any(), any(), any(), any());

        handler.onAuthenticationSuccess(request, response, authToken);

        assertThat(response.getRedirectedUrl()).contains("error=bungie_failed");
    }

    private org.springframework.security.oauth2.client.OAuth2AuthorizedClient osuClient(String refresh) {
        var registration = org.springframework.security.oauth2.client.registration.ClientRegistration
                .withRegistrationId("osu").clientId("12345").redirectUri("http://localhost/cb")
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationUri("https://osu.ppy.sh/oauth/authorize")
                .tokenUri("https://osu.ppy.sh/oauth/token").build();
        var access = new org.springframework.security.oauth2.core.OAuth2AccessToken(
                org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER, "access",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(86_400));
        return new org.springframework.security.oauth2.client.OAuth2AuthorizedClient(registration, "42", access,
                refresh == null ? null : new org.springframework.security.oauth2.core.OAuth2RefreshToken(refresh,
                        java.time.Instant.now()));
    }

    @Test
    void osuLink_isWrittenWithItsRefreshToken_andSpringsCopyIsForgotten() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        userAccount.setTwitchUsername("jeb_");
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("osu");
        when(authToken.getPrincipal()).thenReturn(catapultUser);
        when(authorizedClientRepository.loadAuthorizedClient("osu", authToken, request))
                .thenReturn(osuClient("refresh-token"));
        when(osuLinkProvider.getIfAvailable()).thenReturn(osuLinks);
        var pending = new fr.enimaloc.catapult.service.osu.OsuLinkService.PendingLink(42L, "Inspector", "osu");
        request.setAttribute(fr.enimaloc.catapult.service.osu.OsuLinkService.PENDING_ATTRIBUTE, pending);

        handler.onAuthenticationSuccess(request, response, authToken);

        verify(osuLinks).complete(eq(userAccount), eq(pending), eq("refresh-token"), eq("access"), any());
        verify(authorizedClientRepository).removeAuthorizedClient("osu", authToken, request, response);
        verify(oAuthTokenRepository, never()).save(any());
        verifyNoInteractions(bungieLinks);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://web.example.com/channel/jeb_?linked=osu#api");
    }

    @Test
    void osuLink_withoutARefreshToken_orAFailedWrite_isAFailedLink_andTheTokenIsStillForgotten() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        userAccount.setTwitchUsername("jeb_");
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("osu");
        when(authToken.getPrincipal()).thenReturn(catapultUser);
        when(authorizedClientRepository.loadAuthorizedClient("osu", authToken, request))
                .thenReturn(osuClient(null), osuClient("refresh-token"));
        when(osuLinkProvider.getIfAvailable()).thenReturn(osuLinks);
        var pending = new fr.enimaloc.catapult.service.osu.OsuLinkService.PendingLink(42L, null, null);
        request.setAttribute(fr.enimaloc.catapult.service.osu.OsuLinkService.PENDING_ATTRIBUTE, pending);

        handler.onAuthenticationSuccess(request, response, authToken);

        verify(osuLinks, never()).complete(any(), any(), any(), any(), any());
        verify(authorizedClientRepository).removeAuthorizedClient("osu", authToken, request, response);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://web.example.com/channel/jeb_?error=osu_failed#api");

        response = new MockHttpServletResponse();
        org.mockito.Mockito.doThrow(new IllegalStateException("refresh-token")).when(osuLinks)
                .complete(any(), any(), any(), any(), any());
        handler.onAuthenticationSuccess(request, response, authToken);
        assertThat(response.getRedirectedUrl()).contains("error=osu_failed");
    }

    @Test
    void guestStarConsent_savesTheTokenReconnectsAndGoesBackToTheSettings() throws Exception {
        ReflectionTestUtils.setField(handler, "webUrl", "https://web.example.com");
        userAccount.setTwitchUsername("jeb_");
        var authToken = mock(OAuth2AuthenticationToken.class);
        when(authToken.getAuthorizedClientRegistrationId()).thenReturn("twitch-guest-star");
        when(authToken.getPrincipal()).thenReturn(catapultUser);

        var refreshToken = mock(OAuth2RefreshToken.class);
        when(refreshToken.getTokenValue()).thenReturn("raw-refresh");
        var accessToken = mock(OAuth2AccessToken.class);
        // As Spring reads Twitch's JSON array: "[a," "b]"
        when(accessToken.getScopes()).thenReturn(java.util.Set.of("[user:read:email,", "channel:read:guest_star]"));
        var client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(refreshToken);
        when(client.getAccessToken()).thenReturn(accessToken);
        when(authorizedClientRepository.loadAuthorizedClient("twitch-guest-star", authToken, request))
            .thenReturn(client);
        OAuthToken oauthToken = new OAuthToken();
        when(oAuthTokenRepository.findByUserAndProvider(userAccount, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(oauthToken));
        when(tokenEncryptionService.encrypt("raw-refresh")).thenReturn("encrypted-refresh");

        handler.onAuthenticationSuccess(request, response, authToken);

        assertThat(oauthToken.getRefreshToken()).isEqualTo("encrypted-refresh");
        assertThat(fr.enimaloc.catapult.domain.account.OAuthScopes.parse(oauthToken.getGrantedScopes()))
            .containsExactlyInAnyOrder("user:read:email", "channel:read:guest_star");
        assertThat(oauthToken.getGrantedScopes()).doesNotContain("[", "]", ",");
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(
            fr.enimaloc.catapult.event.GuestStarConsentGrantedEvent.class));
        verify(authorizedClientRepository).removeAuthorizedClient("twitch-guest-star", authToken, request, response);
        assertThat(response.getRedirectedUrl())
            .isEqualTo("https://web.example.com/channel/jeb_?guest_star=ok#settings");
    }
}
