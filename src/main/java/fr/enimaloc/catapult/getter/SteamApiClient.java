package fr.enimaloc.catapult.getter;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public interface SteamApiClient {

    record PlayerSummary(String gameId, String gameName) {}

    default Optional<PlayerSummary> getPlayerSummary(String steamId) {
        return getPlayerSummary(steamId, null);
    }

    Optional<PlayerSummary> getPlayerSummary(String steamId, String personalToken);

    default Map<String, Optional<PlayerSummary>> getPlayerSummaries(Collection<String> steamIds) {
        Map<String, Optional<PlayerSummary>> result = new LinkedHashMap<>();
        for (String id : steamIds) {
            result.put(id, getPlayerSummary(id));
        }
        return result;
    }

    default boolean isProfilePublic(String steamId) {
        return true;
    }

    default boolean isProfilePublic(String steamId, String personalToken) {
        return isProfilePublic(steamId);
    }
}