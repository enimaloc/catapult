package fr.enimaloc.catapult.service.provider;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** The admin probe of the Twitch API: one raw call, answered as the provider sent it. */
@Service
@RequiredArgsConstructor
public class TwitchRawQueries {

    private static final String TWITCH_API_URL = "https://api.twitch.tv/helix";

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    private final TwitchTokenService twitchTokenService;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    public RawProviderResponseSupport.RawProviderResponse games(String name) {
        return rawSupport.fetch(() -> fetchGames(name));
    }

    public RawProviderResponseSupport.RawProviderResponse users(UUID userId, String login) {
        UserAccount user = findUser(userId);
        OAuthToken token = findToken(user);
        return rawSupport.fetch(() -> fetchUsers(token, user, login));
    }

    public RawProviderResponseSupport.RawProviderResponse moderatedChannels(UUID userId) {
        UserAccount user = findUser(userId);
        OAuthToken token = findToken(user);
        return rawSupport.fetch(() -> fetchModeratedChannels(token, user));
    }

    private String fetchGames(String name) {
        String token = twitchTokenService.getAppAccessToken();
        return restClient.get()
                .uri(TWITCH_API_URL + "/games?name=" + name)
                .header("Authorization", "Bearer " + token)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(String.class);
    }

    private String fetchUsers(OAuthToken token, UserAccount user, String login) {
        String accessToken = twitchTokenService.resolveAccessToken(token, user);
        String uri = TWITCH_API_URL + "/users" + (login != null && !login.isBlank() ? "?login=" + login : "");
        return restClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(String.class);
    }

    private String fetchModeratedChannels(OAuthToken token, UserAccount user) {
        String accessToken = twitchTokenService.resolveAccessToken(token, user);
        return restClient.get()
                .uri(TWITCH_API_URL + "/moderation/channels?user_id=" + user.getTwitchId())
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(String.class);
    }

    private OAuthToken findToken(UserAccount user) {
        return oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Twitch token linked to this user"));
    }

    private UserAccount findUser(UUID userId) {
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user"));
    }
}
