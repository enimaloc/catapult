package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.service.AdminMigrationService;
import fr.enimaloc.catapult.service.InviteService;
import fr.enimaloc.catapult.service.WhitelistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Twitch logins through {@link CatapultOAuth2UserService#loadUser}: whitelist, invites, account upkeep. */
class CatapultOAuth2UserServiceLoginTest {

    private static final String USERS = "https://api.twitch.tv/helix/users";

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    private final UserSettingsRepository settings = mock(UserSettingsRepository.class);
    private final GetterConfigRepository getters = mock(GetterConfigRepository.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final WhitelistService whitelist = mock(WhitelistService.class);
    private final AdminMigrationService migrations = mock(AdminMigrationService.class);
    private final InviteService invites = mock(InviteService.class);
    private MockRestServiceServer twitch;
    private CatapultOAuth2UserService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        twitch = MockRestServiceServer.bindTo(builder).build();
        service = new CatapultOAuth2UserService(accounts, tokens, settings, getters, encryption, events,
                builder.build(), whitelist, migrations, invites);
        ReflectionTestUtils.setField(service, "ownerId", "owner-1");
        ReflectionTestUtils.setField(service, "defaultNoGameName", "Just Chatting");
        ReflectionTestUtils.setField(service, "defaultNoGameId", "509658");
        ReflectionTestUtils.setField(service, "defaultIncompleteGameName", "");
        ReflectionTestUtils.setField(service, "defaultIncompleteGameId", "");
        when(encryption.encrypt("access-token")).thenReturn("encrypted");
        when(tokens.findByUserAndProvider(any(), any())).thenReturn(Optional.empty());
        when(accounts.findByTwitchId(any())).thenReturn(Optional.empty());
        when(accounts.findBySystemAccountTrue()).thenReturn(Optional.empty());
        when(accounts.save(any(UserAccount.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static OAuth2UserRequest request(String registrationId) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientId("client-id")
                .redirectUri("http://localhost/callback")
                .authorizationUri("https://id.twitch.tv/oauth2/authorize")
                .tokenUri("https://id.twitch.tv/oauth2/token")
                .userInfoUri(USERS)
                .userNameAttributeName("id")
                .build();
        return new OAuth2UserRequest(registration, new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "access-token", Instant.now(), Instant.now().plusSeconds(3600)));
    }

    private void twitchUser(String id, String login, String image) {
        twitch.expect(requestTo(USERS))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andExpect(header("Client-ID", "client-id"))
                .andRespond(withSuccess("{\"data\": [{\"id\": \"" + id + "\", \"login\": \"" + login + "\""
                        + (image == null ? "" : ", \"profile_image_url\": \"" + image + "\"") + "}]}",
                        MediaType.APPLICATION_JSON));
    }

    private UserAccount existing(String id, String login) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId(id);
        account.setTwitchUsername(login);
        account.setStatus(UserAccount.Status.ACTIVE);
        when(accounts.findByTwitchId(id)).thenReturn(Optional.of(account));
        return account;
    }

    private static void assertErrorCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                e -> assertThat(e.getError().getErrorCode()).isEqualTo(code));
    }

    private void inviteInSession(String code) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(InviteCodeRelayFilter.SESSION_KEY, code);
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }

    @Nested
    class NewAccounts {
        @Test
        void areCreatedWithDefaultSettingsGettersAndToken() {
            twitchUser("tw-1", "streamer", "https://img/1.png");

            OAuth2User user = service.loadUser(request("twitch"));

            assertThat(user).isInstanceOfSatisfying(CatapultOAuth2User.class, u -> {
                assertThat(u.getUserAccount().getTwitchUsername()).isEqualTo("streamer");
                assertThat(u.getUserAccount().getProfileImageUrl()).isEqualTo("https://img/1.png");
            });
            ArgumentCaptor<UserSettings> saved = ArgumentCaptor.forClass(UserSettings.class);
            verify(settings).save(saved.capture());
            assertThat(saved.getValue().getNoGameTwitchGameId()).isEqualTo("509658");
            assertThat(saved.getValue().getIncompleteFallbackTwitchGameId()).isNull();
            verify(getters, times(GetterConfig.Provider.values().length)).save(any(GetterConfig.class));
            ArgumentCaptor<OAuthToken> token = ArgumentCaptor.forClass(OAuthToken.class);
            verify(tokens).save(token.capture());
            assertThat(token.getValue().getAccessToken()).isEqualTo("encrypted");
            assertThat(token.getValue().getProvider()).isEqualTo(OAuthToken.Provider.TWITCH);
            verify(events).publishEvent(any(AccountCreatedEvent.class));
        }

        @Test
        void incompleteFallbackDefaults_areAppliedWhenConfigured() {
            ReflectionTestUtils.setField(service, "defaultIncompleteGameName", "Retro");
            ReflectionTestUtils.setField(service, "defaultIncompleteGameId", "27284");
            ReflectionTestUtils.setField(service, "defaultNoGameId", "");
            twitchUser("tw-1", "streamer", null);

            service.loadUser(request("twitch"));

            ArgumentCaptor<UserSettings> saved = ArgumentCaptor.forClass(UserSettings.class);
            verify(settings).save(saved.capture());
            assertThat(saved.getValue().getIncompleteFallbackTwitchGameName()).isEqualTo("Retro");
            assertThat(saved.getValue().getNoGameTwitchGameId()).isNull();
        }
    }

    @Nested
    class ExistingAccounts {
        @Test
        void keepTheirNameAndPictureInSync_andAreNotAnnouncedAgain() {
            UserAccount account = existing("tw-1", "old_name");
            account.setProfileImageUrl("https://img/old.png");
            twitchUser("tw-1", "new_name", "https://img/new.png");

            service.loadUser(request("twitch"));

            assertThat(account.getTwitchUsername()).isEqualTo("new_name");
            assertThat(account.getProfileImageUrl()).isEqualTo("https://img/new.png");
            verify(events, never()).publishEvent(any());
        }

        @Test
        void reuseTheirTwitchToken() {
            UserAccount account = existing("tw-1", "streamer");
            OAuthToken token = new OAuthToken();
            when(tokens.findByUserAndProvider(account, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(token));
            twitchUser("tw-1", "streamer", null);

            service.loadUser(request("twitch"));

            verify(tokens).save(token);
            assertThat(token.getAccessToken()).isEqualTo("encrypted");
        }

        @Test
        void theSystemAccount_cannotLogIn() {
            existing("tw-bot", "catapult").setSystemAccount(true);
            twitchUser("tw-bot", "catapult", null);

            assertErrorCode(() -> service.loadUser(request("twitch")), "system_account_login_forbidden");
        }
    }

    @Nested
    class Whitelist {
        @BeforeEach
        void enabled() {
            when(whitelist.isEnabled()).thenReturn(true);
        }

        @Test
        void strangersWithoutInvite_areRefused() {
            twitchUser("tw-1", "stranger", null);

            assertErrorCode(() -> service.loadUser(request("twitch")), "not_whitelisted");
            verify(accounts, never()).save(any());
        }

        @Test
        void whitelistedUsersAndTheOwner_getIn() {
            when(whitelist.contains("tw-1")).thenReturn(true);
            twitchUser("tw-1", "friend", null);
            twitchUser("owner-1", "owner", null);

            service.loadUser(request("twitch"));
            OAuth2User owner = service.loadUser(request("twitch"));

            assertThat(owner).isInstanceOf(CatapultOAuth2User.class);
            verify(invites, never()).redeem(any(), any());
        }

        @Test
        void aPendingInvite_isRedeemed_andCanGrantAnInviteOfTheirOwn() {
            inviteInSession("CODE42");
            when(invites.redeem("CODE42", "tw-1")).thenReturn(true);
            twitchUser("tw-1", "invitee", null);

            CatapultOAuth2User user = (CatapultOAuth2User) service.loadUser(request("twitch"));

            verify(invites).grantInvite(user.getUserAccount());
            assertThat(RequestContextHolder.currentRequestAttributes()
                    .getAttribute(InviteCodeRelayFilter.SESSION_KEY, org.springframework.web.context.request.RequestAttributes.SCOPE_SESSION))
                    .isNull();
        }

        @Test
        void aRedeemedInviteWithoutReinviteRights_grantsNothing() {
            inviteInSession("CODE42");
            when(invites.redeem("CODE42", "tw-1")).thenReturn(false);
            twitchUser("tw-1", "invitee", null);

            service.loadUser(request("twitch"));

            verify(invites, never()).grantInvite(any());
        }
    }

    @Nested
    class Failures {
        @Test
        void emptyOrMissingUserInfo_isRejected() {
            twitch.expect(requestTo(USERS)).andRespond(withSuccess("{\"data\": []}", MediaType.APPLICATION_JSON));
            twitch.expect(requestTo(USERS)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            assertErrorCode(() -> service.loadUser(request("twitch")), "empty_user_info_response");
            assertErrorCode(() -> service.loadUser(request("twitch")), "empty_user_info_response");
        }

        @Test
        void bodilessUserInfo_isRejected() {
            twitch.expect(requestTo(USERS)).andRespond(withSuccess());

            assertErrorCode(() -> service.loadUser(request("twitch")), "invalid_user_info_response");
        }

        @Test
        void unexpectedErrors_becomeServerErrors() {
            twitch.expect(requestTo(USERS)).andRespond(withServerError());

            assertErrorCode(() -> service.loadUser(request("twitch")), "server_error");
        }

        @Test
        void secondaryProviders_needATwitchSession() {
            assertErrorCode(() -> service.loadUser(request("steam")), "unauthorized");
            assertErrorCode(() -> service.loadUser(request("xbox")), "unauthorized");
        }
    }
}
