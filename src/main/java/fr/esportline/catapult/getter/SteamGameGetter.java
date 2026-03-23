package fr.esportline.catapult.getter;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Détecte le jeu en cours via ISteamUser/GetPlayerSummaries.
 * Retourne gameid + gameextrainfo si l'utilisateur joue actuellement (profil public requis).
 * Utilise une clé API serveur (steam.api-key) — aucun token utilisateur nécessaire.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SteamGameGetter implements GameGetter {

    @Value("${steam.api-key:}")
    private String steamApiKey;

    private final RestClient restClient;

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        if (steamApiKey.isBlank() || user.getSteamId() == null) return Optional.empty();
        try {
            return fetchCurrentGame(user.getSteamId());
        } catch (Exception e) {
            log.warn("Failed to fetch current game from Steam for user {}: {}", user.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<DetectedGame> fetchCurrentGame(String steamId) {
        String url = UriComponentsBuilder
            .fromUriString("https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/")
            .queryParam("key", steamApiKey)
            .queryParam("steamids", steamId)
            .toUriString();

        Map<String, Object> response = restClient.get()
            .uri(url)
            .retrieve()
            .body(Map.class);

        if (response == null) return Optional.empty();

        Map<String, Object> responseBody = (Map<String, Object>) response.get("response");
        if (responseBody == null) return Optional.empty();

        List<Map<String, Object>> players = (List<Map<String, Object>>) responseBody.get("players");
        if (players == null || players.isEmpty()) return Optional.empty();

        Map<String, Object> player = players.get(0);
        Object gameId = player.get("gameid");
        Object gameName = player.get("gameextrainfo");

        if (gameId == null || gameName == null) return Optional.empty();

        return Optional.of(new DetectedGame(String.valueOf(gameId), GameBinding.SourceType.STEAM, String.valueOf(gameName)));
    }
}
