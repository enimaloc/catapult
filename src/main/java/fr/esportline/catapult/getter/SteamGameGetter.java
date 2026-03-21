package fr.esportline.catapult.getter;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.OAuthToken;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.repository.OAuthTokenRepository;
import fr.esportline.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Détecte le jeu en cours via l'API Steam GetRecentlyPlayedGames (v0001).
 * Le jeu "en cours" correspond au premier appId retourné par l'API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SteamGameGetter implements GameGetter {

    private static final String STEAM_API_URL =
        "https://api.steampowered.com/IPlayerService/GetRecentlyPlayedGames/v0001/";

    private final OAuthTokenRepository oAuthTokenRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final RestClient restClient;

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        return oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.STEAM)
            .flatMap(token -> fetchCurrentGame(token, user));
    }

    @SuppressWarnings("unchecked")
    private Optional<DetectedGame> fetchCurrentGame(OAuthToken token, UserAccount user) {
        try {
            String apiKey = tokenEncryptionService.decrypt(token.getAccessToken());

            Map<String, Object> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .scheme("https")
                    .host("api.steampowered.com")
                    .path("/IPlayerService/GetRecentlyPlayedGames/v0001/")
                    .queryParam("key", apiKey)
                    .queryParam("steamid", user.getTwitchId()) // mapped via UserAccount
                    .queryParam("count", 1)
                    .queryParam("format", "json")
                    .build())
                .retrieve()
                .body(Map.class);

            if (response == null) return Optional.empty();

            Map<String, Object> responseBody = (Map<String, Object>) response.get("response");
            if (responseBody == null) return Optional.empty();

            List<Map<String, Object>> games = (List<Map<String, Object>>) responseBody.get("games");
            if (games == null || games.isEmpty()) return Optional.empty();

            Map<String, Object> game = games.get(0);
            String appId = String.valueOf(game.get("appid"));
            String name = (String) game.get("name");

            return Optional.of(new DetectedGame(appId, GameBinding.SourceType.STEAM, name));
        } catch (Exception e) {
            log.warn("Failed to fetch current game from Steam for user {}: {}", user.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
