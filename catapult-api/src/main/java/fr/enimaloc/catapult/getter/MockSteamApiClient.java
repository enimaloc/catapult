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
    private final Set<String> offlineProfiles = ConcurrentHashMap.newKeySet();
    private volatile boolean rateLimited = false;

    @Override
    public CompletableFuture<Optional<PlayerSummary>> getPlayerSummary(String steamId, String personalToken) {
        return CompletableFuture.completedFuture(Optional.ofNullable(gameByUser.get(steamId)));
    }

    @Override
    public CompletableFuture<SteamProfileStatus> getProfileStatus(String steamId, String personalToken) {
        if (rateLimited) return CompletableFuture.completedFuture(new SteamProfileStatus(false, false));
        boolean profilePublic = !privateProfiles.contains(steamId);
        boolean offlineMode = offlineProfiles.contains(steamId);
        return CompletableFuture.completedFuture(new SteamProfileStatus(profilePublic, offlineMode));
    }

    @Override
    public boolean isRateLimited() {
        return rateLimited;
    }

    public void setGameForUser(String steamId, String gameId, String gameName) {
        gameByUser.put(steamId, new PlayerSummary(gameId, gameName, "MockPlayer", "online"));
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

    public void setOffline(String steamId) {
        offlineProfiles.add(steamId);
        log.info("[Mock Steam] Profile {} set to offline", steamId);
    }

    public void setOnline(String steamId) {
        offlineProfiles.remove(steamId);
        log.info("[Mock Steam] Profile {} set to online", steamId);
    }

    public void setRateLimited(boolean rateLimited) {
        this.rateLimited = rateLimited;
        log.info("[Mock Steam] Rate limit {}", rateLimited ? "enabled" : "disabled");
    }

    public Set<String> getPrivateProfiles() {
        return Collections.unmodifiableSet(privateProfiles);
    }
}
