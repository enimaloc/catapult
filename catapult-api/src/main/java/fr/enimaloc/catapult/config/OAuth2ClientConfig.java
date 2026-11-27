package fr.enimaloc.catapult.config;

import fr.enimaloc.catapult.service.gueststar.GuestStarConsent;
import fr.enimaloc.catapult.service.bungie.BungieCredentialsService;
import fr.enimaloc.catapult.service.osu.OsuCredentialsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.DefaultOAuth2TokenRequestHeadersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    @Value("${battlenet.enabled:false}")
    private boolean battleNetEnabled;

    @Value("${epic.client-id:}")
    private String epicClientId;

    @Value("${epic.client-secret:}")
    private String epicClientSecret;

    @Value("${app.base-url}")
    private String baseUrl;

    /** The Bungie.net OAuth endpoints (client confidential: its secret in the Basic header). */
    static final String BUNGIE_AUTHORIZE = "https://www.bungie.net/en/OAuth/Authorize";
    static final String BUNGIE_TOKEN = "https://www.bungie.net/platform/app/oauth/token/";
    static final String BUNGIE_MEMBERSHIPS = "https://www.bungie.net/Platform/User/GetMembershipsForCurrentUser/";
    /** The osu! OAuth endpoints (its client id and secret in the token request's body, as osu! documents). */
    static final String TWITCH_AUTHORIZE = "https://id.twitch.tv/oauth2/authorize";
    static final String TWITCH_TOKEN = "https://id.twitch.tv/oauth2/token";
    static final String TWITCH_USERS = "https://api.twitch.tv/helix/users";
    /**
     * One per Helix call or EventSub subscription made with the streamer's token (the system bot logs in through the
     * same registration): changing it asks every streamer to log in again.
     */
    static final String[] TWITCH_SCOPES = {
        "user:read:email",                    // GET /users email, at login
        "channel:manage:broadcast",           // PATCH /channels: category, CCLs
        "user:edit:broadcast",                // GET/PUT /users/extensions, GET /users/extensions/list
        "user:read:chat", "user:write:chat",  // EventSub channel.chat.message, POST /chat/messages
        "channel:read:redemptions",           // EventSub channel.channel_points_custom_reward_redemption.add
        "user:read:moderated_channels",       // GET /moderation/channels
        // EventSub channel.guest_star_*, GET /guest_star/session: asked of everyone, used in stream.title.guest_stars
        GuestStarConsent.SCOPE
    };
    static final String OSU_AUTHORIZE = "https://osu.ppy.sh/oauth/authorize";
    static final String OSU_TOKEN = "https://osu.ppy.sh/oauth/token";
    static final String OSU_ME = "https://osu.ppy.sh/api/v2/me";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            ObjectProvider<BungieCredentialsService> bungieCredentials,
            ObjectProvider<OsuCredentialsService> osuCredentials) {
        List<ClientRegistration> registrations = new ArrayList<>();

        registrations.add(authorizationCode("twitch", twitchClientId, twitchClientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .scope(TWITCH_SCOPES)
            .authorizationUri(TWITCH_AUTHORIZE)
            .tokenUri(TWITCH_TOKEN)
            .userInfoUri(TWITCH_USERS)
            .userNameAttributeName("id")
            .build());

        // The Guest Star consent (experiment stream.title.guest_stars), for a token from before the login above asked
        // its scope: the same scopes, its token replacing the Twitch one (same account), back to the settings. Its
        // callback, <base>/login/oauth2/code/twitch-guest-star, must be declared on Twitch's console.
        registrations.add(authorizationCode(GuestStarConsent.REGISTRATION_ID, twitchClientId, twitchClientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .scope(TWITCH_SCOPES)
            .authorizationUri(TWITCH_AUTHORIZE)
            .tokenUri(TWITCH_TOKEN)
            .userInfoUri(TWITCH_USERS)
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
            // No "openid": it would switch Spring to the OIDC flow and bypass CatapultOAuth2UserService; Blizzard's userinfo needs no scope.
            registrations.add(authorizationCode("battlenet", battleNetClientId, battleNetClientSecret)
                .authorizationUri("https://oauth.battle.net/authorize")
                .tokenUri("https://oauth.battle.net/token")
                .userInfoUri("https://oauth.battle.net/userinfo")
                .userNameAttributeName("sub")
                .build());
        }

        if (battleNetEnabled && configured(battleNetClientId, battleNetClientSecret)) {
            // The World of Warcraft import: a consent of its own, so that the link above (and the bot's enrolment)
            // never changes. Its user token is read once and forgotten (TwitchLoginSuccessHandler). No "openid", as
            // above. Its callback, <base>/login/oauth2/code/battlenet-stats, must be declared on Blizzard's portal.
            registrations.add(authorizationCode("battlenet-stats", battleNetClientId, battleNetClientSecret)
                .scope("wow.profile")
                .authorizationUri("https://oauth.battle.net/authorize")
                .tokenUri("https://oauth.battle.net/token")
                .userInfoUri("https://oauth.battle.net/userinfo")
                .userNameAttributeName("sub")
                .build());
        }

        if (configured(epicClientId, epicClientSecret)) {
            // No "openid": it would switch Spring to the OIDC flow and bypass CatapultOAuth2UserService; basic_profile
            // is what EOS needs to answer userInfo with the account id and the display name.
            registrations.add(authorizationCode("epic", epicClientId, epicClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .scope("basic_profile")
                .authorizationUri("https://www.epicgames.com/id/authorize")
                .tokenUri("https://api.epicgames.dev/epic/oauth/v2/token")
                .userInfoUri("https://api.epicgames.dev/epic/oauth/v2/userInfo")
                .userNameAttributeName("sub")
                .build());
        }

        // Bungie: the credentials are an admin's (bungie_app_credentials), read at each lookup, so the login exists
        // only while they are set. Bungie sets the scopes on its portal (none is sent) and has no userinfo endpoint:
        // CatapultOAuth2UserService reads the memberships itself, with the API key. Its callback,
        // <base>/login/oauth2/code/bungie, must be declared on Bungie's portal.
        // osu!: the same, from osu_app_credentials; scopes identify (implicit) and public (the scores), its callback
        // <base>/login/oauth2/code/osu set as the application's callback URL on osu!.
        return new DynamicClientRegistrationRepository(registrations, Map.of("bungie",
            () -> bungieRegistration(bungieCredentials), "osu", () -> osuRegistration(osuCredentials)));
    }

    /** The osu! registration from the admin's credentials; empty when none is set or they cannot be read now. */
    private Optional<ClientRegistration> osuRegistration(ObjectProvider<OsuCredentialsService> credentials) {
        try {
            return Optional.ofNullable(credentials.getIfAvailable())
                .flatMap(OsuCredentialsService::current)
                .map(c -> osu(c.clientId(), c.clientSecret()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The osu! registration of {@code clientId}. */
    ClientRegistration osu(String clientId, String clientSecret) {
        return authorizationCode("osu", clientId, clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .scope("identify", "public")
            .authorizationUri(OSU_AUTHORIZE)
            .tokenUri(OSU_TOKEN)
            .userInfoUri(OSU_ME)
            .userNameAttributeName("id")
            .build();
    }

    /** The Bungie registration from the admin's credentials; empty when none is set or they cannot be read now. */
    private Optional<ClientRegistration> bungieRegistration(ObjectProvider<BungieCredentialsService> credentials) {
        try {
            return Optional.ofNullable(credentials.getIfAvailable())
                .flatMap(BungieCredentialsService::current)
                .map(c -> bungie(c.clientId(), c.clientSecret()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The Bungie.net registration of {@code clientId}. */
    ClientRegistration bungie(String clientId, String clientSecret) {
        return authorizationCode("bungie", clientId, clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationUri(BUNGIE_AUTHORIZE)
            .tokenUri(BUNGIE_TOKEN)
            .userInfoUri(BUNGIE_MEMBERSHIPS)
            .userNameAttributeName("membershipId")
            .build();
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

    /**
     * Spring's authorization-code token client, except that Epic (EOS) gets its client credentials in the Basic
     * header as-is: Spring URL-encodes them, EOS does not decode them and rejects a secret containing "/" or "+".
     */
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient() {
        DefaultOAuth2TokenRequestHeadersConverter<OAuth2AuthorizationCodeGrantRequest> standard =
            new DefaultOAuth2TokenRequestHeadersConverter<>();
        DefaultOAuth2TokenRequestHeadersConverter<OAuth2AuthorizationCodeGrantRequest> unencoded =
            new DefaultOAuth2TokenRequestHeadersConverter<>();
        unencoded.setEncodeClientCredentials(false);

        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setHeadersConverter(request ->
            "epic".equals(request.getClientRegistration().getRegistrationId())
                ? unencoded.convert(request)
                : standard.convert(request));
        return client;
    }
}
