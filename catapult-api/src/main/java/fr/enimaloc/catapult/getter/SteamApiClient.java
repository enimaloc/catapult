package fr.enimaloc.catapult.getter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public interface SteamApiClient {

    record PlayerSummary(String gameId, String gameName) {}

    CompletableFuture<Optional<PlayerSummary>> getPlayerSummary(String steamId, String personalToken);

    default CompletableFuture<Optional<PlayerSummary>> getPlayerSummary(String steamId) {
        return getPlayerSummary(steamId, null);
    }

    /**
     * Fetches summaries for multiple Steam IDs.
     * Default: fans out to individual getPlayerSummary calls.
     * RealSteamApiClient overrides this with a single batch HTTP call.
     */
    default CompletableFuture<Map<String, Optional<PlayerSummary>>> getPlayerSummaries(Collection<String> steamIds) {
        List<CompletableFuture<Map.Entry<String, Optional<PlayerSummary>>>> futures = steamIds.stream()
            .map(id -> getPlayerSummary(id).thenApply(ps -> Map.entry(id, ps)))
            .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(__ -> futures.stream()
                .collect(Collectors.toMap(
                    f -> f.join().getKey(),
                    f -> f.join().getValue()
                )));
    }

    default CompletableFuture<Boolean> isProfilePublic(String steamId) {
        return CompletableFuture.completedFuture(true);
    }

    default CompletableFuture<Boolean> isProfilePublic(String steamId, String personalToken) {
        return isProfilePublic(steamId);
    }

    default CompletableFuture<List<String>> getOwnedGameIds(String steamId) {
        return CompletableFuture.completedFuture(List.of());
    }

    default CompletableFuture<List<String>> getOwnedGameIds(String steamId, String personalToken) {
        return getOwnedGameIds(steamId);
    }

    default boolean isRateLimited() {
        return false;
    }
}