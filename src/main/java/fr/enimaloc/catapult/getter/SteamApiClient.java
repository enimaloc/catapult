package fr.enimaloc.catapult.getter;

import java.util.Optional;

public interface SteamApiClient {

    record PlayerSummary(String gameId, String gameName) {}

    Optional<PlayerSummary> getPlayerSummary(String steamId);

    default boolean isProfilePublic(String steamId) {
        return true;
    }

    default boolean isProfilePublic(String steamId, String personalToken) {
        return isProfilePublic(steamId);
    }
}