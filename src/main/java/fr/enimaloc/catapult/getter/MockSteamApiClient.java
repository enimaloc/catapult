package fr.enimaloc.catapult.getter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@Profile("mock-steam")
public class MockSteamApiClient implements SteamApiClient {

    private final Map<String, PlayerSummary> gameByUser = new ConcurrentHashMap<>();

    @Override
    public Optional<PlayerSummary> getPlayerSummary(String steamId) {
        return Optional.ofNullable(gameByUser.get(steamId));
    }

    @Override
    public List<SteamGame> getPlayerGames(String steamId) {
        return List.of();
    }

    public Map<String, PlayerSummary> getGameByUser() {
        return Map.copyOf(gameByUser);
    }

    public void setGameForUser(String steamId, String gameId, String gameName) {
        gameByUser.put(steamId, new PlayerSummary(gameId, gameName));
        log.info("[Mock Steam] Game for {} → {} ({})", steamId, gameName, gameId);
    }

    public void clearGameForUser(String steamId) {
        gameByUser.remove(steamId);
        log.info("[Mock Steam] Game for {} cleared", steamId);
    }
}
