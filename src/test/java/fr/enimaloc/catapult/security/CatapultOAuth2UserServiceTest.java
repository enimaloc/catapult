package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.service.WhitelistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
class CatapultOAuth2UserServiceTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private GetterConfigRepository getterConfigRepository;
    @Mock private TokenEncryptionService tokenEncryptionService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RestClient restClient;
    @Mock private WhitelistService whitelistService;

    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private CatapultOAuth2UserService service;

    @BeforeEach
    void setUp() {
        service = new CatapultOAuth2UserService(
            userAccountRepository, oAuthTokenRepository, userSettingsRepository,
            getterConfigRepository, tokenEncryptionService, eventPublisher, restClient, whitelistService
        );
        ReflectionTestUtils.setField(service, "ownerId", "");
        ReflectionTestUtils.setField(service, "defaultNoGameName", "");
        ReflectionTestUtils.setField(service, "defaultNoGameId", "");
        ReflectionTestUtils.setField(service, "defaultIncompleteGameName", "");
        ReflectionTestUtils.setField(service, "defaultIncompleteGameId", "");
    }

    private OAuth2UserRequest buildRequest() {
        ClientRegistration reg = ClientRegistration.withRegistrationId("twitch")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .clientId("client-id")
            .redirectUri("http://localhost/callback")
            .authorizationUri("https://id.twitch.tv/oauth2/authorize")
            .tokenUri("https://id.twitch.tv/oauth2/token")
            .userInfoUri("https://api.twitch.tv/helix/users")
            .userNameAttributeName("id")
            .build();
        OAuth2AccessToken token = new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, "access-token", Instant.now(), Instant.now().plusSeconds(3600)
        );
        return new OAuth2UserRequest(reg, token);
    }

    private void mockTwitchUserInfo(String twitchId, String login) {
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of("data",
            List.of(Map.of("id", twitchId, "login", login, "profile_image_url", ""))
        ));
        when(whitelistService.isEnabled()).thenReturn(false);
        when(tokenEncryptionService.encrypt(anyString())).thenReturn("encrypted");
    }

    @Test
    void login_reactivatesInactiveAccount() {
        UserAccount inactive = new UserAccount();
        inactive.setId(UUID.randomUUID());
        inactive.setTwitchId("twitch-123");
        inactive.setTwitchUsername("old_name");
        inactive.setStatus(UserAccount.Status.INACTIVE);
        when(userAccountRepository.findByTwitchId("twitch-123")).thenReturn(Optional.of(inactive));
        when(userAccountRepository.save(any())).thenReturn(inactive);
        when(oAuthTokenRepository.findByUserAndProvider(any(), any())).thenReturn(Optional.empty());
        mockTwitchUserInfo("twitch-123", "new_name");

        service.loadUser(buildRequest());

        assertThat(inactive.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
        assertThat(inactive.getTwitchUsername()).isEqualTo("new_name");
    }
}
