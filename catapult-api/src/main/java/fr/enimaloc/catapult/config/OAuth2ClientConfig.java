package fr.enimaloc.catapult.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.ArrayList;
import java.util.List;

@Configuration
@Profile("!mock-web")
public class OAuth2ClientConfig {

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    @Value("${twitch.client-secret:}")
    private String twitchClientSecret;

    @Value("${xbox.client-id:}")
    private String xboxClientId;

    @Value("${xbox.client-secret:}")
    private String xboxClientSecret;

    @Value("${battlenet.client-id:}")
    private String battleNetClientId;

    @Value("${battlenet.client-secret:}")
    private String battleNetClientSecret;

    @Value("${app.base-url}")
    private String baseUrl;

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        List<ClientRegistration> registrations = new ArrayList<>();

        registrations.add(authorizationCode("twitch", twitchClientId, twitchClientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .scope("user:read:email", "channel:manage:broadcast",
                   "user:read:chat", "user:write:chat",
                   "channel:moderate", "channel:read:redemptions", "user:read:moderated_channels",
                   "moderator:read:followers", "moderator:manage:shoutouts")
            .authorizationUri("https://id.twitch.tv/oauth2/authorize")
            .tokenUri("https://id.twitch.tv/oauth2/token")
            .userInfoUri("https://api.twitch.tv/helix/users")
            .userNameAttributeName("id")
            .build());

        if (configured(xboxClientId, xboxClientSecret)) {
            registrations.add(authorizationCode("xbox", xboxClientId, xboxClientSecret)
                .scope("XboxLive.signin", "XboxLive.offline_access")
                .authorizationUri("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize")
                .tokenUri("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
                .userInfoUri("https://graph.microsoft.com/v1.0/me")
                .userNameAttributeName("id")
                .build());
        }

        if (configured(battleNetClientId, battleNetClientSecret)) {
            registrations.add(authorizationCode("battlenet", battleNetClientId, battleNetClientSecret)
                .scope("openid")
                .authorizationUri("https://oauth.battle.net/authorize")
                .tokenUri("https://oauth.battle.net/token")
                .userInfoUri("https://oauth.battle.net/userinfo")
                .userNameAttributeName("sub")
                .build());
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }

    /** An authorization-code registration redirecting back to this API's login callback. */
    private ClientRegistration.Builder authorizationCode(String registrationId, String clientId, String clientSecret) {
        return ClientRegistration.withRegistrationId(registrationId)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(baseUrl + "/login/oauth2/code/{registrationId}");
    }

    /** Optional providers are only registered once both credentials are set. */
    private static boolean configured(String clientId, String clientSecret) {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenResponseClient() {
        return new RestClientRefreshTokenTokenResponseClient();
    }
}
