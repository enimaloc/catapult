package fr.enimaloc.catapult.getter.steam;

import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@Profile("!mock")
@ConditionalOnBooleanProperty("steam.enabled")
public class RealSteamApiClient implements SteamApiClient {

    private static final String PLAYER_SUMMARIES_URL =
        "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/";
    private static final String OWNED_GAMES_URL =
        "https://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/";

    private static final int DEFAULT_RETRY_AFTER_SECONDS = 60;

    private static final String RESPONSE_KEY = "response";
    private static final String STEAMID_KEY  = "steamid";

    @Value("${steam.profile-cache.ttl:PT15M}")
    private Duration profileCacheTtl;

    private final RestClient restClient;
    private final SteamRateLimiter rateLimiter;
    private final Executor steamExecutor;
    private final SteamApiKeyRotator rotator;
    private final ExternalApiObservations apiObservations;
    private final Map<CacheKey, CachedProfileStatus> profileCache = new ConcurrentHashMap<>();

    @Autowired
    public RealSteamApiClient(RestClient restClient, SteamRateLimiter rateLimiter,
                               @Qualifier("steamExecutor") Executor steamExecutor,
                               SteamApiKeyRotator rotator,
                               ExternalApiObservations apiObservations) {
        this.restClient = restClient;
        this.rateLimiter = rateLimiter;
        this.steamExecutor = steamExecutor;
        this.rotator = rotator;
        this.apiObservations = apiObservations;
    }

    // -------------------------------------------------------------------------
    // SteamApiClient implementation
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<PlayerSummary>> getPlayerSummary(String steamId, String personalToken) {
        return CompletableFuture.supplyAsync(
            () -> fetchPlayer(steamId, personalToken).flatMap(RealSteamApiClient::whilePlaying), steamExecutor);
    }

    @Override
    public CompletableFuture<Optional<PlayerSummary>> getPlayerProfile(String steamId, String personalToken) {
        return CompletableFuture.supplyAsync(
            () -> fetchPlayer(steamId, personalToken).map(RealSteamApiClient::summaryOf), steamExecutor);
    }

    @Override
    public CompletableFuture<SteamApiClient.SteamProfileStatus> getProfileStatus(String steamId, String personalToken) {
        CacheKey key = cacheKey(steamId, personalToken);
        CachedProfileStatus cached = profileCache.get(key);
        if (cached != null && cached.isValid()) {
            return CompletableFuture.completedFuture(cached.toStatus());
        }
        return CompletableFuture.supplyAsync(() -> {
            CachedProfileStatus fresh = profileCache.compute(key, (k, existing) -> {
                if (existing != null && existing.isValid()) return existing;
                SteamApiClient.SteamProfileStatus result = fetchProfileStatus(steamId, personalToken);
                return new CachedProfileStatus(result.profilePublic(), result.offlineMode(), Instant.now().plus(profileCacheTtl));
            });
            return fresh.toStatus();
        }, steamExecutor);
    }

    @Override
    public CompletableFuture<Map<String, Optional<PlayerSummary>>> getPlayerSummaries(Collection<String> steamIds) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Optional<PlayerSummary>> result = new HashMap<>();
            List<String> idList = new ArrayList<>(steamIds);
            for (int i = 0; i < idList.size(); i += 100) {
                result.putAll(fetchPlayerBatch(idList.subList(i, Math.min(i + 100, idList.size()))));
            }
            return result;
        }, steamExecutor);
    }

    @Override
    public CompletableFuture<List<String>> getOwnedGameIds(String steamId) {
        return getOwnedGameIds(steamId, null);
    }

    @Override
    public CompletableFuture<List<String>> getOwnedGameIds(String steamId, String personalToken) {
        return CompletableFuture.supplyAsync(() -> withOwnedGames("get_owned_games", "owned game fetch", steamId,
            personalToken, false, List.<String>of(), games -> games.stream()
                .map(g -> g.get("appid"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList()), steamExecutor);
    }

    @Override
    public CompletableFuture<Optional<Duration>> getPlaytime(String steamId, String appId, String personalToken) {
        return CompletableFuture.supplyAsync(() -> withOwnedGames("get_playtime", "playtime fetch", steamId,
            personalToken, true, Optional.<Duration>empty(), games -> games.stream()
                .filter(g -> appId.equals(String.valueOf(g.get("appid"))))
                .findFirst()
                .map(g -> g.get("playtime_forever"))
                .filter(Objects::nonNull)
                .map(minutes -> Duration.ofMinutes(((Number) minutes).longValue()))), steamExecutor);
    }

    @Override
    public Duration getProfileCacheTtl() {
        return profileCacheTtl;
    }

    @Override
    public void invalidateProfileCache(String steamId, String personalToken) {
        profileCache.remove(cacheKey(steamId, personalToken));
    }

    // -------------------------------------------------------------------------
    // Steam calls (synchronous, run on the Steam executor)
    // -------------------------------------------------------------------------

    private Map<String, Optional<PlayerSummary>> fetchPlayerBatch(List<String> steamIds) {
        return apiObservations.observe("steam", "get_player_summaries", () -> {
            Map<String, Optional<PlayerSummary>> result = steamIds.stream()
                .collect(Collectors.toMap(id -> id, id -> Optional.empty()));
            return attempt("player batch fetch", steamIds.size() + " users", null, result, key -> {
                List<Map<String, Object>> players = fetchList("players",
                    PLAYER_SUMMARIES_URL + "?key={key}&steamids={steamids}", key, String.join(",", steamIds));
                if (players != null) {
                    for (Map<String, Object> player : players) {
                        result.put(String.valueOf(player.get(STEAMID_KEY)), whilePlaying(player));
                    }
                }
                return result;
            });
        });
    }

    private Optional<Map<String, Object>> fetchPlayer(String steamId, String personalToken) {
        return apiObservations.observe("steam", "get_player_summary", () ->
            attempt("player fetch", steamId, personalToken, Optional.<Map<String, Object>>empty(), key -> {
                List<Map<String, Object>> players = fetchList("players",
                    PLAYER_SUMMARIES_URL + "?key={key}&steamids={steamids}", key, steamId);
                return players == null || players.isEmpty() ? Optional.empty() : Optional.of(players.getFirst());
            }));
    }

    private SteamApiClient.SteamProfileStatus fetchProfileStatus(String steamId, String personalToken) {
        Optional<Map<String, Object>> playerOpt = fetchPlayer(steamId, personalToken);
        if (playerOpt.isEmpty()) {
            return new SteamApiClient.SteamProfileStatus(false, false);
        }
        Map<String, Object> player = playerOpt.get();

        Object visibilityObj = player.get("communityvisibilitystate");
        boolean profileVisible = visibilityObj != null && ((Number) visibilityObj).intValue() == 3;

        Object personaStateObj = player.get("personastate");
        boolean offlineMode = personaStateObj == null || ((Number) personaStateObj).intValue() == 0;

        if (!profileVisible) {
            return new SteamApiClient.SteamProfileStatus(false, offlineMode);
        }
        return new SteamApiClient.SteamProfileStatus(isGameListVisible(steamId, personalToken), offlineMode);
    }

    /** Steam only reports a {@code game_count} when the profile's game list is public. */
    private boolean isGameListVisible(String steamId, String personalToken) {
        return apiObservations.observe("steam", "check_game_list_visibility", () ->
            attempt("game list visibility check", steamId, personalToken, false, key -> {
                Map<String, Object> body = fetchResponse(OWNED_GAMES_URL + "?key={key}&steamid={steamid}", key, steamId);
                return body != null && body.containsKey("game_count");
            }));
    }

    /**
     * Reads the owned-games list, retrying on the next pooled key after a 429 until the pool is
     * exhausted; a personal token gets a single try. Any other failure yields {@code none}.
     */
    private <T> T withOwnedGames(String operation, String purpose, String steamId, String personalToken,
                                 boolean includePlayedFreeGames, T none, Function<List<Map<String, Object>>, T> read) {
        return apiObservations.observe("steam", operation, () -> {
            while (true) {
                Optional<ApiKey> picked = pickKey(personalToken, purpose, steamId);
                if (picked.isEmpty()) return none;
                ApiKey key = picked.get();
                if (!rateLimiter.acquireBlocking(key.value())) return none;

                try {
                    List<Map<String, Object>> games = includePlayedFreeGames
                        ? fetchList("games", OWNED_GAMES_URL + "?key={key}&steamid={steamid}&include_appinfo={includeAppinfo}"
                                + "&include_played_free_games={includePlayedFree}", key.value(), steamId, 1, 1)
                        : fetchList("games", OWNED_GAMES_URL + "?key={key}&steamid={steamid}&include_appinfo={includeAppinfo}",
                            key.value(), steamId, 1);
                    return games == null ? none : read.apply(games);
                } catch (HttpClientErrorException.TooManyRequests e) {
                    int retryAfter = onTooManyRequests(key, e);
                    log.warn("Steam API 429 during {} for {}: retry after {}s", purpose, steamId, retryAfter);
                    if (!key.pooled() || rotator.isAllKeysBlocked()) return none;
                } catch (Exception e) {
                    log.warn("Steam {} failed for {}: {}", purpose, steamId, e.getMessage());
                    return none;
                }
            }
        });
    }

    /**
     * A single non-blocking call under the caller's key: no key, no rate budget, a 429 (which
     * throttles that key) or any other failure all yield {@code none}.
     */
    private <T> T attempt(String purpose, String target, String personalToken, T none, Function<String, T> call) {
        Optional<ApiKey> picked = pickKey(personalToken, purpose, target);
        if (picked.isEmpty()) return none;
        ApiKey key = picked.get();
        if (!rateLimiter.acquire(key.value())) {
            log.warn("Steam rate limit reached, skipping {} for {}", purpose, target);
            return none;
        }

        try {
            return call.apply(key.value());
        } catch (HttpClientErrorException.TooManyRequests e) {
            int retryAfter = onTooManyRequests(key, e);
            log.warn("Steam API 429 during {} for {}: retry after {}s", purpose, target, retryAfter);
            return none;
        } catch (Exception e) {
            log.warn("Steam {} failed for {}: {}", purpose, target, e.getMessage());
            return none;
        }
    }

    // -------------------------------------------------------------------------
    // Keys and responses
    // -------------------------------------------------------------------------

    /** The key a call runs under: the streamer's own token, or one from the shared pool. */
    private record ApiKey(String value, boolean pooled) {}

    /** The personal token when there is one, otherwise the pool's next available key. */
    private Optional<ApiKey> pickKey(String personalToken, String purpose, String target) {
        if (hasText(personalToken)) return Optional.of(new ApiKey(personalToken, false));
        Optional<ApiKey> pooled = rotator.nextKey().map(key -> new ApiKey(key, true));
        if (pooled.isEmpty()) log.warn("No Steam API key available for {} {}", purpose, target);
        return pooled;
    }

    /** Throttles the key that got a 429 (in the pool, or in the rate limiter for a personal token). */
    private int onTooManyRequests(ApiKey key, HttpClientErrorException e) {
        int retryAfter = parseRetryAfter(e);
        if (key.pooled()) rotator.onKeyRateLimited(key.value(), retryAfter);
        else rateLimiter.onRateLimitResponse(key.value(), retryAfter);
        return retryAfter;
    }

    private int parseRetryAfter(HttpClientErrorException e) {
        try {
            var headers = e.getResponseHeaders();
            String header = headers != null ? headers.getFirst("Retry-After") : null;
            return header != null ? Integer.parseInt(header) : DEFAULT_RETRY_AFTER_SECONDS;
        } catch (NumberFormatException ex) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
    }

    /** The {@code response} object of a Steam Web API reply, or null when absent. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchResponse(String uriTemplate, Object... uriVariables) {
        Map<String, Object> reply = restClient.get().uri(uriTemplate, uriVariables).retrieve().body(Map.class);
        return reply == null ? null : (Map<String, Object>) reply.get(RESPONSE_KEY);
    }

    /** The {@code listKey} array inside the reply's {@code response}, or null when either is absent. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchList(String listKey, String uriTemplate, Object... uriVariables) {
        Map<String, Object> body = fetchResponse(uriTemplate, uriVariables);
        return body == null ? null : (List<Map<String, Object>>) body.get(listKey);
    }

    // -------------------------------------------------------------------------
    // Player mapping
    // -------------------------------------------------------------------------

    private static PlayerSummary summaryOf(Map<String, Object> player) {
        return new PlayerSummary(stringOrNull(player.get("gameid")), stringOrNull(player.get("gameextrainfo")),
            stringOrNull(player.get("personaname")), onlineStatus(player.get("personastate")));
    }

    /** The player's summary while they are in a game (both its id and name known), empty otherwise. */
    private static Optional<PlayerSummary> whilePlaying(Map<String, Object> player) {
        boolean playing = player.get("gameid") != null && player.get("gameextrainfo") != null;
        return playing ? Optional.of(summaryOf(player)) : Optional.empty();
    }

    private static String stringOrNull(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    /** Maps Steam's numeric personastate to the small fixed vocabulary chat commands read. */
    private static String onlineStatus(Object personaState) {
        int state = personaState instanceof Number n ? n.intValue() : 0;
        return switch (state) {
            case 1 -> "online";
            case 2 -> "busy";
            case 3 -> "away";
            case 4 -> "snooze";
            case 5 -> "looking to trade";
            case 6 -> "looking to play";
            default -> "offline";
        };
    }

    private static boolean hasText(String token) {
        return token != null && !token.isBlank();
    }

    private static CacheKey cacheKey(String steamId, String personalToken) {
        return new CacheKey(steamId, hasText(personalToken) ? personalToken : "");
    }

    private record CacheKey(String steamId, String tokenKey) {}

    private record CachedProfileStatus(boolean isPublic, boolean offlineMode, Instant expiresAt) {
        boolean isValid() { return Instant.now().isBefore(expiresAt); }
        SteamApiClient.SteamProfileStatus toStatus() { return new SteamApiClient.SteamProfileStatus(isPublic, offlineMode); }
    }
}
