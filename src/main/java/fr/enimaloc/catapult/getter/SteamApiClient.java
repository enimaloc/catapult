package fr.enimaloc.catapult.getter;

import java.util.Optional;

public interface SteamApiClient {

    record PlayerSummary(String gameId, String gameName) {}

    Optional<PlayerSummary> getPlayerSummary(String steamId);
}