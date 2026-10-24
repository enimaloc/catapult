package fr.enimaloc.catapult.getter.steam;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.GameGetter;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.debug.BatchDebugFilter;
import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("steam.enabled")
public class SteamGameGetter implements GameGetter {

    static final String PLAYER_SUMMARIES_OPERATION = "steam.get_player_summaries";
    /** Same bound as the scheduler's wait on the prefetch. */
    private static final long CAPTURED_BATCH_TIMEOUT_SECONDS = 5;

    private final SteamApiClient steamApiClient;
    private final TokenEncryptionService tokenEncryptionService;
    private final SteamStoreService steamStoreService;

    private volatile Map<String, Optional<SteamApiClient.PlayerSummary>> cycleCache = Map.of();

    private DebugRecording debug = DebugRecording.OFF;

    /** Records this class's grouped calls and incoming messages for the members in debug mode. */
    @Autowired(required = false)
    public void setDebugRecording(DebugRecording debug) {
        this.debug = debug == null ? DebugRecording.OFF : debug;
    }

    @Override
    public String name() {
        return "Steam";
    }

    public CompletableFuture<Void> prefetchBatch(List<UserAccount> users) {
        List<UserAccount> batched = users.stream()
            .filter(u -> u.getSteamId() != null && u.getSteamPersonalToken() == null)
            .toList();
        List<String> batchIds = batched.stream().map(UserAccount::getSteamId).toList();

        if (batchIds.isEmpty()) {
            cycleCache = Map.of();
            return CompletableFuture.completedFuture(null);
        }

        List<DebugRecording.Member> members = batched.stream()
            .map(u -> new DebugRecording.Member(u.getId(), u.getSteamId()))
            .toList();
        // A debugged member in the batch: the call is captured, so it is waited for here (bounded by the
        // scheduler's own prefetch timeout). Without one, batch() just starts it, as before.
        return debug.batch(PLAYER_SUMMARIES_OPERATION, members, BatchDebugFilter::steam, () -> {
            CompletableFuture<Map<String, Optional<SteamApiClient.PlayerSummary>>> call =
                steamApiClient.getPlayerSummaries(batchIds);
            if (DebugContext.capturing()) {
                awaitCaptured(call);
            }
            return call;
        }).thenAccept(result -> cycleCache = result);
    }

    /** Waits for a captured batch so that its exchanges are in the capture; its outcome stays in the future. */
    private static void awaitCaptured(CompletableFuture<?> call) {
        try {
            call.get(CAPTURED_BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            // the caller sees it through the future
        }
    }

    public void clearCycleCache() {
        cycleCache = Map.of();
    }

    private DetectedGame toDetectedGame(SteamApiClient.PlayerSummary p) {
        if (p.gameId() == null) {
            return new DetectedGame(null, GameBinding.SourceType.STEAM, p.gameName());
        }
        return steamStoreService.resolveEffectiveApp(p.gameId())
            .map(parent -> new DetectedGame(parent.appId(), GameBinding.SourceType.STEAM, parent.name()))
            .orElseGet(() -> new DetectedGame(p.gameId(), GameBinding.SourceType.STEAM, p.gameName()));
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        if (user.getSteamId() == null) return Optional.empty();
        try {
            if (cycleCache.containsKey(user.getSteamId())) {
                return cycleCache.get(user.getSteamId()).map(this::toDetectedGame);
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
                .thenApply(opt -> opt.map(this::toDetectedGame))
                .join();
        } catch (Exception e) {
            log.warn("Failed to fetch current game from Steam for user {}: {}", user.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
