package fr.enimaloc.catapult.getter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@Profile("!mock")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("steam.enabled")
public class RealSteamApiClient implements SteamApiClient {

    private static final String PLAYER_SUMMARIES_URL =
        "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/";
    private static final String OWNED_GAMES_URL =
        "https://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/";

    @Value("${steam.api-key:}")
    private String steamApiKey;

    private final RestClient restClient;

    @Override
    public Optional<PlayerSummary> getPlayerSummary(String steamId) {
        return fetchPlayer(steamId).flatMap(player -> {
            Object gameId   = player.get("gameid");
            Object gameName = player.get("gameextrainfo");
            if (gameId == null || gameName == null) return Optional.empty();
            return Optional.of(new PlayerSummary(String.valueOf(gameId), String.valueOf(gameName)));
        });
    }

    @Override
    public boolean isProfilePublic(String steamId) {
        boolean visibilityPublic = fetchPlayer(steamId)
            .map(player -> {
                Object visibility = player.get("communityvisibilitystate");
                return visibility != null && ((Number) visibility).intValue() == 3;
            })
            .orElse(false);
        if (!visibilityPublic) return false;
        return isGameListVisible(steamId, null);
    }

    @Override
    public boolean isProfilePublic(String steamId, String personalToken) {
        boolean visibilityPublic = fetchPlayer(steamId)
            .map(player -> {
                Object visibility = player.get("communityvisibilitystate");
                return visibility != null && ((Number) visibility).intValue() == 3;
            })
            .orElse(false);
        if (!visibilityPublic) return false;
        return isGameListVisible(steamId, personalToken);
    }

    @SuppressWarnings("unchecked")
    private boolean isGameListVisible(String steamId, String personalToken) {
        String key = (personalToken != null && !personalToken.isBlank()) ? personalToken : steamApiKey;
        if (key.isBlank()) return false;

        String url = UriComponentsBuilder
            .fromUriString(OWNED_GAMES_URL)
            .queryParam("key", key)
            .queryParam("steamid", steamId)
            .toUriString();

        try {
            Map<String, Object> response = restClient.get()
                .uri(url)
                .retrieve()
                .body(Map.class);
            if (response == null) return false;

            Map<String, Object> responseBody = (Map<String, Object>) response.get("response");
            return responseBody != null && responseBody.containsKey("game_count");
        } catch (Exception e) {
            log.warn("Failed to check Steam game list visibility for {}: {}", steamId, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> fetchPlayer(String steamId) {
        if (steamApiKey.isBlank()) return Optional.empty();

        String url = UriComponentsBuilder
            .fromUriString(PLAYER_SUMMARIES_URL)
            .queryParam("key", steamApiKey)
            .queryParam("steamids", steamId)
            .toUriString();

        try {
            Map<String, Object> response = restClient.get()
                .uri(url)
                .retrieve()
                .body(Map.class);
            if (response == null) return Optional.empty();

            Map<String, Object> responseBody = (Map<String, Object>) response.get("response");
            if (responseBody == null) return Optional.empty();

            List<Map<String, Object>> players = (List<Map<String, Object>>) responseBody.get("players");
            if (players == null || players.isEmpty()) return Optional.empty();

            return Optional.of(players.getFirst());
        } catch (Exception e) {
            log.warn("Failed to fetch Steam player data for {}: {}", steamId, e.getMessage());
            return Optional.empty();
        }
    }
}
