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
 * Détecte le jeu en cours via l'API Discord GET /users/@me.
 * Seules les activités de type PLAYING sont retenues.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscordGameGetter implements GameGetter {

    private static final String DISCORD_API_URL = "https://discord.com/api/users/@me";
    private static final String ACTIVITY_TYPE_PLAYING = "0"; // Discord type 0 = Playing

    private final OAuthTokenRepository oAuthTokenRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final RestClient restClient;

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        return oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.DISCORD)
            .flatMap(token -> fetchCurrentGame(token, user));
    }

    @SuppressWarnings("unchecked")
    private Optional<DetectedGame> fetchCurrentGame(OAuthToken token, UserAccount user) {
        try {
            String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());

            Map<String, Object> response = restClient.get()
                .uri(DISCORD_API_URL)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);

            if (response == null) return Optional.empty();

            List<Map<String, Object>> activities = (List<Map<String, Object>>) response.get("activities");
            if (activities == null) return Optional.empty();

            return activities.stream()
                .filter(a -> ACTIVITY_TYPE_PLAYING.equals(String.valueOf(a.get("type"))))
                .findFirst()
                .map(a -> {
                    String name = (String) a.get("name");
                    String applicationId = String.valueOf(a.get("application_id"));
                    return new DetectedGame(applicationId, GameBinding.SourceType.DISCORD, name);
                });
        } catch (Exception e) {
            log.warn("Failed to fetch current game from Discord for user {}: {}", user.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
