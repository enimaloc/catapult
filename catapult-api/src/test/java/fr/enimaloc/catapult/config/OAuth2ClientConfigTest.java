package fr.enimaloc.catapult.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** Which OAuth2 providers get registered, and how. */
class OAuth2ClientConfigTest {

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
        ClientRegistrationRepository repository = config.clientRegistrationRepository();

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

        ClientRegistrationRepository repository = config.clientRegistrationRepository();

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
        assertThat(config.clientRegistrationRepository().findByRegistrationId("battlenet-stats")).isNull();

        ReflectionTestUtils.setField(config, "battleNetClientId", "bn-id");
        assertThat(config.clientRegistrationRepository().findByRegistrationId("battlenet-stats")).isNull();

        ReflectionTestUtils.setField(config, "battleNetClientSecret", "bn-secret");
        ReflectionTestUtils.setField(config, "battleNetEnabled", false);
        ClientRegistrationRepository disabled = config.clientRegistrationRepository();
        assertThat(disabled.findByRegistrationId("battlenet-stats")).isNull();
        // The Battle.net link itself does not depend on battlenet.enabled (unchanged)
        assertThat(disabled.findByRegistrationId("battlenet")).isNotNull();
    }

    @Test
    void statsRegistrationAsksWowProfileOnly() {
        ReflectionTestUtils.setField(config, "battleNetEnabled", true);
        ReflectionTestUtils.setField(config, "battleNetClientId", "bn-id");
        ReflectionTestUtils.setField(config, "battleNetClientSecret", "bn-secret");

        ClientRegistrationRepository repository = config.clientRegistrationRepository();

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
        assertThat(config.clientRegistrationRepository().findByRegistrationId("epic")).isNull();

        ReflectionTestUtils.setField(config, "epicClientSecret", "ep-secret");
        ClientRegistration epic = config.clientRegistrationRepository().findByRegistrationId("epic");

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

        ClientRegistration xbox = config.clientRegistrationRepository().findByRegistrationId("xbox");

        assertThat(xbox.getScopes()).containsExactlyInAnyOrder("XboxLive.signin", "XboxLive.offline_access");
        assertThat(xbox.getRedirectUri()).isEqualTo("https://api.test/login/oauth2/code/{registrationId}");
    }

    @Test
    void refreshTokens_useTheRestClientResponseClient() {
        assertThat(config.refreshTokenResponseClient()).isInstanceOf(RestClientRefreshTokenTokenResponseClient.class);
    }
}
