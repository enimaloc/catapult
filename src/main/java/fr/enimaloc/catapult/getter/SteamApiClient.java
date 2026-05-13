package fr.enimaloc.catapult.getter;

import java.util.List;
import java.util.Optional;

public interface SteamApiClient {

    record PlayerSummary(String gameId, String gameName) {}

    record SteamGame(String id) {}

    Optional<PlayerSummary> getPlayerSummary(String steamId);

    List<SteamGame> getPlayerGames(String steamId);
}