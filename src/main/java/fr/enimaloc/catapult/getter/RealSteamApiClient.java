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

    @Value("${steam.api-key:}")
    private String steamApiKey;

    private final RestClient restClient;

    @Override
    @SuppressWarnings("unchecked")
    public Optional<PlayerSummary> getPlayerSummary(String steamId) {
        if (steamApiKey.isBlank()) return Optional.empty();

        String url = UriComponentsBuilder
            .fromUriString("https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/")
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

            Map<String, Object> player = players.get(0);
            Object gameId   = player.get("gameid");
            Object gameName = player.get("gameextrainfo");
            if (gameId == null || gameName == null) return Optional.empty();

            return Optional.of(new PlayerSummary(String.valueOf(gameId), String.valueOf(gameName)));
        } catch (Exception e) {
            log.warn("Failed to fetch player summary from Steam for {}: {}", steamId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SteamGame> getPlayerGames(String steamId) {
        if (steamApiKey.isBlank()) return List.of();

        String url = UriComponentsBuilder
                .fromUriString("https://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/")
                .queryParam("key", steamApiKey)
                .queryParam("steamid", steamId)
                .toUriString();

        try {
            Map<String, Object> response = restClient.get()
                .uri(url)
                .retrieve()
                .body(Map.class);

            if (response == null) return List.of();

            Map<String, Object> responseBody = (Map<String, Object>) response.get("response");
            if (responseBody == null) return List.of();

            List<Map<String, Object>> games = (List<Map<String, Object>>) responseBody.get("games");
            if (games == null) return List.of();

            return games.stream()
                .map(g -> new SteamGame(String.valueOf(g.get("appid"))))
                .toList();
        } catch (Exception e) {
            log.warn("Failed to fetch player games from Steam for {}: {}", steamId, e.getMessage());
            return List.of();
        }
    }
}
