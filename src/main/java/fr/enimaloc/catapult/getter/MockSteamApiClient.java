package fr.enimaloc.catapult.getter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@Profile("mock-steam")
public class MockSteamApiClient implements SteamApiClient {

    private final Map<String, PlayerSummary> gameByUser = new ConcurrentHashMap<>();
    private final Set<String> privateProfiles = ConcurrentHashMap.newKeySet();

    @Override
    public CompletableFuture<Optional<PlayerSummary>> getPlayerSummary(String steamId, String personalToken) {
        return CompletableFuture.completedFuture(Optional.ofNullable(gameByUser.get(steamId)));
    }

    @Override
    public CompletableFuture<Boolean> isProfilePublic(String steamId) {
        return CompletableFuture.completedFuture(!privateProfiles.contains(steamId));
    }

    public void setGameForUser(String steamId, String gameId, String gameName) {
        gameByUser.put(steamId, new PlayerSummary(gameId, gameName));
        log.info("[Mock Steam] Game for {} → {} ({})", steamId, gameName, gameId);
    }

    public void clearGameForUser(String steamId) {
        gameByUser.remove(steamId);
        log.info("[Mock Steam] Game for {} cleared", steamId);
    }

    public void setProfilePrivate(String steamId) {
        privateProfiles.add(steamId);
        log.info("[Mock Steam] Profile {} set to private", steamId);
    }

    public void setProfilePublic(String steamId) {
        privateProfiles.remove(steamId);
        log.info("[Mock Steam] Profile {} set to public", steamId);
    }

    public Set<String> getPrivateProfiles() {
        return Collections.unmodifiableSet(privateProfiles);
    }
}
