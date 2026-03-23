package fr.esportline.catapult.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.ArrayList;
import java.util.List;

@Configuration
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

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        List<ClientRegistration> registrations = new ArrayList<>();

        registrations.add(ClientRegistration.withRegistrationId("twitch")
            .clientId(twitchClientId)
            .clientSecret(twitchClientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .scope("user:read:email", "channel:manage:broadcast")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("https://id.twitch.tv/oauth2/authorize")
            .tokenUri("https://id.twitch.tv/oauth2/token")
            .userInfoUri("https://api.twitch.tv/helix/users")
            .userNameAttributeName("id")
            .build());

        if (!xboxClientId.isBlank() && !xboxClientSecret.isBlank()) {
            registrations.add(ClientRegistration.withRegistrationId("xbox")
                .clientId(xboxClientId)
                .clientSecret(xboxClientSecret)
                .scope("openid", "XboxLive.signin", "XboxLive.offline_access")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize")
                .tokenUri("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
                .userInfoUri("https://graph.microsoft.com/v1.0/me")
                .userNameAttributeName("id")
                .build());
        }

        if (!battleNetClientId.isBlank() && !battleNetClientSecret.isBlank()) {
            registrations.add(ClientRegistration.withRegistrationId("battlenet")
                .clientId(battleNetClientId)
                .clientSecret(battleNetClientSecret)
                .scope("openid")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://oauth.battle.net/authorize")
                .tokenUri("https://oauth.battle.net/token")
                .userInfoUri("https://oauth.battle.net/userinfo")
                .userNameAttributeName("sub")
                .build());
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }
}
