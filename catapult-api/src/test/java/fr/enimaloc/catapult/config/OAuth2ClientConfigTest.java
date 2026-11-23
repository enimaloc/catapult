package fr.enimaloc.catapult.config;

import fr.enimaloc.catapult.service.bungie.BungieCredentialsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which OAuth2 providers get registered, and how. */
class OAuth2ClientConfigTest {

    private static final ObjectProvider<BungieCredentialsService> NO_BUNGIE =
            new StaticListableBeanFactory().getBeanProvider(BungieCredentialsService.class);

    private final OAuth2ClientConfig config = new OAuth2ClientConfig();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(config, "twitchClientId", "tw-id");
        ReflectionTestUtils.setField(config, "twitchClientSecret", "tw-secret");
        ReflectionTestUtils.setField(config, "baseUrl", "https://api.test");
        for (String field : new String[]{"xboxClientId", "xboxClientSecret", "battleNetClientId", "battleNetClientSecret",
            "epicClientId", "epicClientSecret"}) {
            ReflectionTestUtils.setField(config, field, "");
        }
    }

    @Test
    void twitchIsAlwaysRegistered_withItsScopesAndPostAuth() {
        ClientRegistrationRepository repository = config.clientRegistrationRepository(NO_BUNGIE);

        ClientRegistration twitch = repository.findByRegistrationId("twitch");
        assertThat(twitch.getClientId()).isEqualTo("tw-id");
        assertThat(twitch.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(twitch.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(twitch.getRedirectUri()).isEqualTo("https://api.test/login/oauth2/code/{registrationId}");
        assertThat(twitch.getScopes()).contains("user:write:chat", "user:edit:broadcast")
                .doesNotContain("moderator:manage:shoutouts");
        assertThat(twitch.getProviderDetails().getUserInfoEndpoint().getUri()).isEqualTo("https://api.twitch.tv/helix/users");
        assertThat(repository.findByRegistrationId("xbox")).isNull();
        assertThat(repository.findByRegistrationId("battlenet")).isNull();
    }

    @Test
    void optionalProviders_needBothCredentials() {
        ReflectionTestUtils.setField(config, "xboxClientId", "xb-id");
        ReflectionTestUtils.setField(config, "battleNetClientId", "bn-id");
        ReflectionTestUtils.setField(config, "battleNetClientSecret", "bn-secret");

        ClientRegistrationRepository repository = config.clientRegistrationRepository(NO_BUNGIE);

        assertThat(repository.findByRegistrationId("xbox")).isNull();
        ClientRegistration battlenet = repository.findByRegistrationId("battlenet");
        // "openid" would switch Spring to the OIDC flow and bypass CatapultOAuth2UserService.
        assertThat(battlenet.getScopes()).doesNotContain("openid");
        assertThat(battlenet.getProviderDetails().getAuthorizationUri()).isEqualTo("https://oauth.battle.net/authorize");
        assertThat(battlenet.getProviderDetails().getTokenUri()).isEqualTo("https://oauth.battle.net/token");
        assertThat(battlenet.getProviderDetails().getUserInfoEndpoint().getUri()).isEqualTo("https://oauth.battle.net/userinfo");
        assertThat(battlenet.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("sub");
    }

    @Test
    void statsRegistrationOnlyWhenBattleNetIsConfigured() {
        ReflectionTestUtils.setField(config, "battleNetEnabled", true);
        assertThat(config.clientRegistrationRepository(NO_BUNGIE).findByRegistrationId("battlenet-stats")).isNull();

        ReflectionTestUtils.setField(config, "battleNetClientId", "bn-id");
        assertThat(config.clientRegistrationRepository(NO_BUNGIE).findByRegistrationId("battlenet-stats")).isNull();

        ReflectionTestUtils.setField(config, "battleNetClientSecret", "bn-secret");
        ReflectionTestUtils.setField(config, "battleNetEnabled", false);
        ClientRegistrationRepository disabled = config.clientRegistrationRepository(NO_BUNGIE);
        assertThat(disabled.findByRegistrationId("battlenet-stats")).isNull();
        // The Battle.net link itself does not depend on battlenet.enabled (unchanged)
        assertThat(disabled.findByRegistrationId("battlenet")).isNotNull();
    }

    @Test
    void statsRegistrationAsksWowProfileOnly() {
        ReflectionTestUtils.setField(config, "battleNetEnabled", true);
        ReflectionTestUtils.setField(config, "battleNetClientId", "bn-id");
        ReflectionTestUtils.setField(config, "battleNetClientSecret", "bn-secret");

        ClientRegistrationRepository repository = config.clientRegistrationRepository(NO_BUNGIE);

        ClientRegistration stats = repository.findByRegistrationId("battlenet-stats");
        assertThat(stats.getScopes()).containsExactly("wow.profile");
        assertThat(stats.getClientId()).isEqualTo("bn-id");
        assertThat(stats.getClientSecret()).isEqualTo("bn-secret");
        assertThat(stats.getRedirectUri()).isEqualTo("https://api.test/login/oauth2/code/{registrationId}");
        assertThat(stats.getProviderDetails().getAuthorizationUri()).isEqualTo("https://oauth.battle.net/authorize");
        assertThat(stats.getProviderDetails().getTokenUri()).isEqualTo("https://oauth.battle.net/token");
        assertThat(stats.getProviderDetails().getUserInfoEndpoint().getUri()).isEqualTo("https://oauth.battle.net/userinfo");
        assertThat(stats.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("sub");
        // The bot's link stays as it was: no scope at all, so no wow.profile and no openid
        ClientRegistration battlenet = repository.findByRegistrationId("battlenet");
        assertThat(battlenet.getScopes()).isEmpty();
    }

    @Test
    void epicClient_isRegisteredOnlyWhenConfigured_withoutOpenid() {
        ReflectionTestUtils.setField(config, "epicClientId", "ep-id");
        assertThat(config.clientRegistrationRepository(NO_BUNGIE).findByRegistrationId("epic")).isNull();

        ReflectionTestUtils.setField(config, "epicClientSecret", "ep-secret");
        ClientRegistration epic = config.clientRegistrationRepository(NO_BUNGIE).findByRegistrationId("epic");

        // "openid" would switch Spring to the OIDC flow and bypass CatapultOAuth2UserService.
        assertThat(epic.getScopes()).containsExactly("basic_profile");
        assertThat(epic.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(epic.getRedirectUri()).isEqualTo("https://api.test/login/oauth2/code/{registrationId}");
        assertThat(epic.getProviderDetails().getAuthorizationUri()).isEqualTo("https://www.epicgames.com/id/authorize");
        assertThat(epic.getProviderDetails().getTokenUri()).isEqualTo("https://api.epicgames.dev/epic/oauth/v2/token");
        assertThat(epic.getProviderDetails().getUserInfoEndpoint().getUri())
                .isEqualTo("https://api.epicgames.dev/epic/oauth/v2/userInfo");
        assertThat(epic.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("sub");
    }

    @Test
    void xbox_isRegisteredOnceConfigured() {
        ReflectionTestUtils.setField(config, "xboxClientId", "xb-id");
        ReflectionTestUtils.setField(config, "xboxClientSecret", "xb-secret");

        ClientRegistration xbox = config.clientRegistrationRepository(NO_BUNGIE).findByRegistrationId("xbox");

        assertThat(xbox.getScopes()).containsExactlyInAnyOrder("XboxLive.signin", "XboxLive.offline_access");
        assertThat(xbox.getRedirectUri()).isEqualTo("https://api.test/login/oauth2/code/{registrationId}");
    }

    @Test
    void refreshTokens_useTheRestClientResponseClient() {
        assertThat(config.refreshTokenResponseClient()).isInstanceOf(RestClientRefreshTokenTokenResponseClient.class);
    }

    @Test
    void bungie_isRegisteredOnlyWhileAnAdminSetItsCredentials() {
        BungieCredentialsService credentials = mock(BungieCredentialsService.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("bungieCredentials", credentials);
        when(credentials.current()).thenReturn(Optional.empty());
        ClientRegistrationRepository repository =
                config.clientRegistrationRepository(beans.getBeanProvider(BungieCredentialsService.class));

        assertThat(repository.findByRegistrationId("bungie")).isNull();

        when(credentials.current()).thenReturn(Optional.of(
                new BungieCredentialsService.Credentials("12345", "bungie-secret-value", "0123456789abcdef0123456789abcdef")));
        ClientRegistration bungie = repository.findByRegistrationId("bungie");

        assertThat(bungie.getClientId()).isEqualTo("12345");
        assertThat(bungie.getClientSecret()).isEqualTo("bungie-secret-value");
        assertThat(bungie.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        // Bungie sets the scopes on its portal: none is asked for
        assertThat(bungie.getScopes()).isEmpty();
        assertThat(bungie.getRedirectUri()).isEqualTo("https://api.test/login/oauth2/code/{registrationId}");
        assertThat(bungie.getProviderDetails().getAuthorizationUri())
                .isEqualTo("https://www.bungie.net/en/OAuth/Authorize");
        assertThat(bungie.getProviderDetails().getTokenUri())
                .isEqualTo("https://www.bungie.net/platform/app/oauth/token/");
        List<String> ids = new ArrayList<>();
        ((Iterable<ClientRegistration>) repository).forEach(r -> ids.add(r.getRegistrationId()));
        // Iterating (the filter chain's login links) never reads the run-time registrations
        assertThat(ids).contains("twitch").doesNotContain("bungie");

        when(credentials.current()).thenReturn(Optional.empty());
        assertThat(repository.findByRegistrationId("bungie")).isNull();
        assertThat(repository.findByRegistrationId("twitch")).isNotNull();
    }

    @Test
    void bungie_unreadableCredentials_areNoRegistration_neverAnError() {
        BungieCredentialsService credentials = mock(BungieCredentialsService.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("bungieCredentials", credentials);
        when(credentials.current()).thenThrow(new IllegalStateException("database down"));
        ClientRegistrationRepository repository =
                config.clientRegistrationRepository(beans.getBeanProvider(BungieCredentialsService.class));

        assertThat(repository.findByRegistrationId("bungie")).isNull();
        assertThat(repository.findByRegistrationId("twitch")).isNotNull();
        ((Iterable<ClientRegistration>) repository).forEach(r -> { });
        org.mockito.Mockito.verify(credentials, org.mockito.Mockito.times(1)).current();
    }

    @Test
    void bungie_isAbsentWithoutTheCredentialsService() {
        assertThat(config.clientRegistrationRepository(NO_BUNGIE).findByRegistrationId("bungie")).isNull();
    }
}
