package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.service.AdminMigrationService;
import fr.enimaloc.catapult.service.InviteService;
import fr.enimaloc.catapult.service.WhitelistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
    @Mock private AdminMigrationService adminMigrationService;
    @Mock private InviteService inviteService;

    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private CatapultOAuth2UserService service;

    @BeforeEach
    void setUp() {
        service = new CatapultOAuth2UserService(
            userAccountRepository, oAuthTokenRepository, userSettingsRepository,
            getterConfigRepository, tokenEncryptionService, eventPublisher, restClient, whitelistService,
            adminMigrationService, inviteService
        );
        ReflectionTestUtils.setField(service, "ownerId", "");
        ReflectionTestUtils.setField(service, "defaultNoGameName", "");
        ReflectionTestUtils.setField(service, "defaultNoGameId", "");
        ReflectionTestUtils.setField(service, "defaultIncompleteGameName", "");
        ReflectionTestUtils.setField(service, "defaultIncompleteGameId", "");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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

    private UserAccount systemAccount() {
        UserAccount s = new UserAccount();
        s.setId(UUID.randomUUID());
        s.setSystemAccount(true);
        s.setTwitchUsername("Catapult");
        s.setStatus(UserAccount.Status.ACTIVE);
        return s;
    }

    @Test
    void createNewAccount_systemAccountExists_copiesSettingsAndGetters() {
        UserAccount system = systemAccount();
        when(userAccountRepository.findBySystemAccountTrue()).thenReturn(Optional.of(system));

        UserAccount newAccount = new UserAccount();
        newAccount.setId(UUID.randomUUID());
        when(userAccountRepository.save(any(UserAccount.class))).thenReturn(newAccount);

        ReflectionTestUtils.invokeMethod(service, "createNewAccount", "twitchId123", "streamer");

        verify(adminMigrationService).migrate(
            eq(system),
            eq(newAccount),
            eq(new AdminMigrationService.MigrateOptions(true, true, false))
        );
        verify(userSettingsRepository, never()).save(any(UserSettings.class));
        verify(getterConfigRepository, never()).save(any(GetterConfig.class));
    }

    @Test
    void createNewAccount_noSystemAccount_initializesDefaults() {
        when(userAccountRepository.findBySystemAccountTrue()).thenReturn(Optional.empty());

        UserAccount newAccount = new UserAccount();
        newAccount.setId(UUID.randomUUID());
        when(userAccountRepository.save(any(UserAccount.class))).thenReturn(newAccount);

        ReflectionTestUtils.invokeMethod(service, "createNewAccount", "twitchId123", "streamer");

        verify(adminMigrationService, never()).migrate(any(), any(), any());
        verify(userSettingsRepository).save(any(UserSettings.class));
        verify(getterConfigRepository, times(GetterConfig.Provider.values().length))
            .save(any(GetterConfig.class));
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

    @Test
    void login_reactivatesPendingDeletionAccount() {
        UserAccount pendingDeletion = new UserAccount();
        pendingDeletion.setId(UUID.randomUUID());
        pendingDeletion.setTwitchId("twitch-456");
        pendingDeletion.setTwitchUsername("old_name");
        pendingDeletion.setStatus(UserAccount.Status.PENDING_DELETION);
        pendingDeletion.setDeletionRequestedAt(java.time.Instant.now());
        when(userAccountRepository.findByTwitchId("twitch-456")).thenReturn(Optional.of(pendingDeletion));
        when(userAccountRepository.save(any())).thenReturn(pendingDeletion);
        when(oAuthTokenRepository.findByUserAndProvider(any(), any())).thenReturn(Optional.empty());
        mockTwitchUserInfo("twitch-456", "new_name");

        service.loadUser(buildRequest());

        assertThat(pendingDeletion.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
        assertThat(pendingDeletion.getDeletionRequestedAt()).isNull();
    }

    @Test
    void handleSecondaryLink_xbox_createsAndEnablesGetterConfig() {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        CatapultOAuth2User principal = new CatapultOAuth2User(null, account, false);
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken(principal, null));

        when(getterConfigRepository.findByUserAndProvider(account, GetterConfig.Provider.XBOX))
            .thenReturn(Optional.empty());
        when(getterConfigRepository.findByUserOrderByPriorityAsc(account)).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(service, "handleSecondaryLink", buildRequest(), OAuthToken.Provider.XBOX);

        var captor = org.mockito.ArgumentCaptor.forClass(GetterConfig.class);
        verify(getterConfigRepository).save(captor.capture());
        assertThat(captor.getValue().getProvider()).isEqualTo(GetterConfig.Provider.XBOX);
        assertThat(captor.getValue().isEnabled()).isTrue();
    }

    @Test
    void handleSecondaryLink_xbox_reEnablesExistingDisabledGetterConfig() {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        CatapultOAuth2User principal = new CatapultOAuth2User(null, account, false);
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken(principal, null));

        GetterConfig existing = new GetterConfig();
        existing.setUser(account);
        existing.setProvider(GetterConfig.Provider.XBOX);
        existing.setEnabled(false);
        when(getterConfigRepository.findByUserAndProvider(account, GetterConfig.Provider.XBOX))
            .thenReturn(Optional.of(existing));

        ReflectionTestUtils.invokeMethod(service, "handleSecondaryLink", buildRequest(), OAuthToken.Provider.XBOX);

        assertThat(existing.isEnabled()).isTrue();
        verify(getterConfigRepository).save(existing);
    }

    @Test
    void handleSecondaryLink_steam_doesNotTouchGetterConfig() {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        CatapultOAuth2User principal = new CatapultOAuth2User(null, account, false);
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken(principal, null));

        ReflectionTestUtils.invokeMethod(service, "handleSecondaryLink", buildRequest(), OAuthToken.Provider.STEAM);

        verify(getterConfigRepository, never()).findByUserAndProvider(any(), any());
        verify(getterConfigRepository, never()).save(any(GetterConfig.class));
    }

    @Test
    void handleSecondaryLink_noAuthenticatedTwitchSession_throws() {
        SecurityContextHolder.clearContext();

        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,
            () -> ReflectionTestUtils.invokeMethod(service, "handleSecondaryLink", buildRequest(), OAuthToken.Provider.XBOX));
    }
}
