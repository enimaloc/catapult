package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.steam.RealSteamApiClient;
import fr.enimaloc.catapult.getter.steam.SteamApiClient;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulatedData;
import fr.enimaloc.catapult.simulation.SimulatedData.SimulatedPresence;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Steam Web API, in the mode of the account the Steam id belongs to ({@code steam.presence},
 * {@code steam.profile-status}, {@code steam.owned-games}); an id no account holds is simulated. {@code REAL} hands the
 * call to {@link RealSteamApiClient}. Otherwise nothing leaves:
 * <ul>
 *   <li>the player plays the account's simulated Steam game, hidden while its profile is private (as Steam does);</li>
 *   <li>the profile is public as the simulated data says, the library is empty, no playtime is known;</li>
 *   <li>a failure answers what the real client answers on any failure: nothing played, a private profile, no
 *   games.</li>
 * </ul>
 * A batch sends the real accounts' ids in one real call and answers the others from the simulation. The answer is
 * asynchronous, as the real client's is, so a simulated latency counts against the caller's timeout. A simulated
 * failure, a 429 included, never reaches {@code SteamRateLimiter} or the key rotator: deliberately, a made-up
 * rate limit must not throttle the real keys.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = {"catapult.simulation.enabled", "steam.enabled"})
public class SimulatedSteamApiClient implements SteamApiClient {

    private static final Executor SIMULATED = Executors.newVirtualThreadPerTaskExecutor();

    private final ObjectProvider<RealSteamApiClient> real;
    private final SimulatedCallRunner runner;
    private final SimulationRegistry registry;
    private final UserAccountRepository accounts;

    @Override
    public CompletableFuture<Optional<PlayerSummary>> getPlayerSummary(String steamId, String personalToken) {
        return playerSummary(steamId, accountOf(steamId), personalToken);
    }

    /** {@link #getPlayerSummary} for an id whose account is already looked up. */
    private CompletableFuture<Optional<PlayerSummary>> playerSummary(String steamId, @Nullable UserAccount holder,
                                                                     String personalToken) {
        return call(steamId, holder, SimulatedCalls.STEAM_PRESENCE, "GET GetPlayerSummaries",
                () -> real().getPlayerSummary(steamId, personalToken),
                account -> playing(account).map(game -> summary(account, game)),
                Optional.empty());
    }

    @Override
    public CompletableFuture<Optional<PlayerSummary>> getPlayerProfile(String steamId, String personalToken) {
        return call(steamId, SimulatedCalls.STEAM_PRESENCE, "GET GetPlayerSummaries (profile)",
                () -> real().getPlayerProfile(steamId, personalToken),
                account -> Optional.of(summary(account, playing(account).orElse(null))),
                Optional.empty());
    }

    @Override
    public CompletableFuture<Map<String, Optional<PlayerSummary>>> getPlayerSummaries(Collection<String> steamIds) {
        List<String> realIds = new ArrayList<>();
        Map<String, @Nullable UserAccount> simulatedIds = new HashMap<>();
        for (String steamId : steamIds) {
            UserAccount account = accountOf(steamId);
            boolean realCall = registry.setting(account == null ? null : account.getId(),
                    SimulatedCalls.STEAM_PRESENCE).mode() == CallSetting.Mode.REAL;
            if (realCall) {
                realIds.add(steamId);
            } else {
                simulatedIds.put(steamId, account);
            }
        }
        CompletableFuture<Map<String, Optional<PlayerSummary>>> realBatch = realIds.isEmpty()
                ? CompletableFuture.completedFuture(Map.of())
                : real().getPlayerSummaries(realIds);
        List<CompletableFuture<Map.Entry<String, Optional<PlayerSummary>>>> simulated = simulatedIds.entrySet().stream()
                .map(id -> playerSummary(id.getKey(), id.getValue(), null)
                        .thenApply(summary -> Map.entry(id.getKey(), summary)))
                .toList();
        return CompletableFuture.allOf(simulated.toArray(new CompletableFuture[0]))
                .thenCombine(realBatch, (done, fromSteam) -> {
                    Map<String, Optional<PlayerSummary>> result = new HashMap<>(fromSteam);
                    simulated.forEach(entry -> result.put(entry.join().getKey(), entry.join().getValue()));
                    return result;
                });
    }

    @Override
    public CompletableFuture<SteamProfileStatus> getProfileStatus(String steamId, String personalToken) {
        return call(steamId, SimulatedCalls.STEAM_PROFILE_STATUS, "GET GetPlayerSummaries (visibility)",
                () -> real().getProfileStatus(steamId, personalToken),
                account -> new SteamProfileStatus(data(account).steamProfilePublic(), false),
                new SteamProfileStatus(false, false));
    }

    @Override
    public CompletableFuture<List<String>> getOwnedGameIds(String steamId) {
        return getOwnedGameIds(steamId, null);
    }

    @Override
    public CompletableFuture<List<String>> getOwnedGameIds(String steamId, String personalToken) {
        return call(steamId, SimulatedCalls.STEAM_OWNED_GAMES, "GET GetOwnedGames",
                () -> real().getOwnedGameIds(steamId, personalToken), account -> List.of(), List.of());
    }

    @Override
    public CompletableFuture<Optional<Duration>> getPlaytime(String steamId, String appId, String personalToken) {
        return call(steamId, SimulatedCalls.STEAM_OWNED_GAMES, "GET GetOwnedGames (playtime " + appId + ")",
                () -> real().getPlaytime(steamId, appId, personalToken), account -> Optional.empty(),
                Optional.empty());
    }

    // local state of the real client: no call goes out

    @Override
    public boolean isRateLimited() {
        RealSteamApiClient client = real.getIfAvailable();
        return client != null && client.isRateLimited();
    }

    @Override
    public Duration getProfileCacheTtl() {
        RealSteamApiClient client = real.getIfAvailable();
        return client != null ? client.getProfileCacheTtl() : SteamApiClient.super.getProfileCacheTtl();
    }

    @Override
    public void invalidateProfileCache(String steamId, String personalToken) {
        RealSteamApiClient client = real.getIfAvailable();
        if (client != null) {
            client.invalidateProfileCache(steamId, personalToken);
        }
    }

    /**
     * Runs one call in the mode of the account holding {@code steamId}, asynchronously so that the latency comes
     * before the answer: the real future's outcome, or a simulated answer, {@code none} on a simulated failure.
     */
    private <T> CompletableFuture<T> call(String steamId, CallKey key, String request,
                                          Supplier<CompletableFuture<T>> realCall,
                                          Function<@Nullable UserAccount, T> simulated, T none) {
        return call(steamId, accountOf(steamId), key, request, realCall, simulated, none);
    }

    private <T> CompletableFuture<T> call(String steamId, @Nullable UserAccount account, CallKey key, String request,
                                          Supplier<CompletableFuture<T>> realCall,
                                          Function<@Nullable UserAccount, T> simulated, T none) {
        UUID accountId = account == null ? null : account.getId();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return runner.run(accountId, key, request + " " + steamId,
                        () -> realCall.get().join(), () -> simulated.apply(account), SimulatedCallRunner::httpError);
            } catch (RestClientException e) {
                log.warn("Simulated Steam {} failed for {}: {}", key.id(), steamId, e.getMessage());
                return none;
            }
        }, SIMULATED);
    }

    private @Nullable UserAccount accountOf(String steamId) {
        return steamId == null ? null : accounts.findBySteamId(steamId).orElse(null);
    }

    private SimulatedData data(@Nullable UserAccount account) {
        return account == null ? SimulatedData.defaults() : registry.data(account.getId());
    }

    /** The account's simulated Steam game, absent when nothing is played or the profile hides it. */
    private Optional<SimulatedPresence> playing(@Nullable UserAccount account) {
        SimulatedData data = data(account);
        if (!data.steamProfilePublic()) {
            return Optional.empty();
        }
        return Optional.ofNullable(data.presence().get(GetterConfig.Provider.STEAM));
    }

    private static PlayerSummary summary(@Nullable UserAccount account, @Nullable SimulatedPresence game) {
        String displayName = account != null && account.getTwitchUsername() != null
                ? account.getTwitchUsername() : "Simulated player";
        return new PlayerSummary(game == null ? null : game.sourceId(), game == null ? null : game.name(),
                displayName, "online");
    }

    private RealSteamApiClient real() {
        RealSteamApiClient client = real.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("The real Steam client is not available (steam.enabled)");
        }
        return client;
    }
}
