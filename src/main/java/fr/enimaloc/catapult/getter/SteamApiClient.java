package fr.enimaloc.catapult.getter;

import java.util.Optional;

public interface SteamApiClient {

    record PlayerSummary(String gameId, String gameName) {}

    default Optional<PlayerSummary> getPlayerSummary(String steamId) {
        return getPlayerSummary(steamId, null);
    }

    Optional<PlayerSummary> getPlayerSummary(String steamId, String personalToken);

    default boolean isProfilePublic(String steamId) {
        return true;
    }

    default boolean isProfilePublic(String steamId, String personalToken) {
        return isProfilePublic(steamId);
    }
}