package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwitchLoginSuccessHandlerTest {

    @Mock OAuth2AuthorizedClientRepository authorizedClientRepository;
    @Mock OAuthTokenRepository oAuthTokenRepository;
    @Mock TokenEncryptionService tokenEncryptionService;

    @InjectMocks TwitchLoginSuccessHandler handler;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private UserAccount userAccount;
    private CatapultOAuth2User catapultUser;

    @BeforeEach
    void setup() {
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
}
