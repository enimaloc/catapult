package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("steam.enabled")
public class SteamGameGetter implements GameGetter {

    private final SteamApiClient steamApiClient;
    private final TokenEncryptionService tokenEncryptionService;

    private volatile Map<String, Optional<SteamApiClient.PlayerSummary>> cycleCache = Map.of();

    @Override
    public String name() {
        return "Steam";
    }

    public CompletableFuture<Void> prefetchBatch(List<UserAccount> users) {
        List<String> batchIds = users.stream()
            .filter(u -> u.getSteamId() != null && u.getSteamPersonalToken() == null)
            .map(UserAccount::getSteamId)
            .toList();

        if (batchIds.isEmpty()) {
            cycleCache = Map.of();
            return CompletableFuture.completedFuture(null);
        }

        return steamApiClient.getPlayerSummaries(batchIds)
            .thenAccept(result -> cycleCache = result);
    }

    public void clearCycleCache() {
        cycleCache = Map.of();
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        if (user.getSteamId() == null) return Optional.empty();
        try {
            if (cycleCache.containsKey(user.getSteamId())) {
                return cycleCache.get(user.getSteamId())
                    .map(p -> new DetectedGame(p.gameId(), GameBinding.SourceType.STEAM, p.gameName()));
            }
            String decryptedPersonalToken = user.getSteamPersonalToken() != null
                ? tokenEncryptionService.decrypt(user.getSteamPersonalToken())
                : null;
            return steamApiClient.getPlayerSummary(user.getSteamId(), decryptedPersonalToken)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("Steam getPlayerSummary timed out or failed for {}: {}", user.getSteamId(), e.getMessage());
                    return Optional.empty();
                })
                .thenApply(opt -> opt.map(p -> new DetectedGame(p.gameId(), GameBinding.SourceType.STEAM, p.gameName())))
                .join();
        } catch (Exception e) {
            log.warn("Failed to fetch current game from Steam for user {}: {}", user.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
