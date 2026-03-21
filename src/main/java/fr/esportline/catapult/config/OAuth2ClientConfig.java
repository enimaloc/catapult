package fr.esportline.catapult.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class OAuth2ClientConfig {

    @Value("${TWITCH_CLIENT_ID:}")
    private String twitchClientId;

    @Value("${TWITCH_CLIENT_SECRET:}")
    private String twitchClientSecret;

    @Value("${STEAM_CLIENT_ID:}")
    private String steamClientId;

    @Value("${STEAM_CLIENT_SECRET:}")
    private String steamClientSecret;

    @Value("${DISCORD_CLIENT_ID:}")
    private String discordClientId;

    @Value("${DISCORD_CLIENT_SECRET:}")
    private String discordClientSecret;

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        List<ClientRegistration> registrations = new ArrayList<>();

        registrations.add(ClientRegistration.withRegistrationId("twitch")
            .clientId(twitchClientId)
            .clientSecret(twitchClientSecret)
            .scope("user:read:email", "channel:manage:broadcast")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("https://id.twitch.tv/oauth2/authorize")
            .tokenUri("https://id.twitch.tv/oauth2/token")
            .userInfoUri("https://api.twitch.tv/helix/users")
            .userNameAttributeName("id")
            .build());

        if (!steamClientId.isBlank() && !steamClientSecret.isBlank()) {
            registrations.add(ClientRegistration.withRegistrationId("steam")
                .clientId(steamClientId)
                .clientSecret(steamClientSecret)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://steamcommunity.com/openid/login")
                .tokenUri("https://steamcommunity.com/openid/login")
                .userInfoUri("https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/")
                .userNameAttributeName("steamid")
                .build());
        }

        if (!discordClientId.isBlank() && !discordClientSecret.isBlank()) {
            registrations.add(ClientRegistration.withRegistrationId("discord")
                .clientId(discordClientId)
                .clientSecret(discordClientSecret)
                .scope("identify", "activities.read")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://discord.com/oauth2/authorize")
                .tokenUri("https://discord.com/api/oauth2/token")
                .userInfoUri("https://discord.com/api/users/@me")
                .userNameAttributeName("id")
                .build());
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }
}
