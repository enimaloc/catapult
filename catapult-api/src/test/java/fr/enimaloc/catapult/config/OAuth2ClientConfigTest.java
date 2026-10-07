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
        for (String field : new String[]{"xboxClientId", "xboxClientSecret", "battleNetClientId", "battleNetClientSecret"}) {
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
        assertThat(twitch.getScopes()).contains("user:write:chat", "moderator:manage:shoutouts");
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
        assertThat(battlenet.getScopes()).containsExactly("openid");
        assertThat(battlenet.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("sub");
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
