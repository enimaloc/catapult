package fr.enimaloc.catapult.getter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

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
    private final Map<CacheKey, CachedProfileStatus> profileCache = new ConcurrentHashMap<>();

    @Autowired
    public RealSteamApiClient(RestClient restClient, SteamRateLimiter rateLimiter,
                               @Qualifier("steamExecutor") Executor steamExecutor,
                               SteamApiKeyRotator rotator) {
        this.restClient = restClient;
        this.rateLimiter = rateLimiter;
        this.steamExecutor = steamExecutor;
        this.rotator = rotator;
    }

    // -------------------------------------------------------------------------
    // SteamApiClient implementation
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<PlayerSummary>> getPlayerSummary(String steamId, String personalToken) {
        return CompletableFuture.supplyAsync(
            () -> fetchPlayer(steamId, personalToken).flatMap(player -> {
                Object gameId   = player.get("gameid");
                Object gameName = player.get("gameextrainfo");
                if (gameId == null || gameName == null) return Optional.empty();
                return Optional.of(new PlayerSummary(String.valueOf(gameId), String.valueOf(gameName)));
            }),
            steamExecutor
        );
    }

    @Override
    public CompletableFuture<Boolean> isProfilePublic(String steamId) {
        return isProfilePublic(steamId, null);
    }

    @Override
    public CompletableFuture<Boolean> isProfilePublic(String steamId, String personalToken) {
        String tokenKey = (personalToken != null && !personalToken.isBlank()) ? personalToken : "";
        CacheKey key = new CacheKey(steamId, tokenKey);
        CachedProfileStatus cached = profileCache.get(key);
        if (cached != null && cached.isValid()) {
            return CompletableFuture.completedFuture(cached.isPublic());
        }
        return CompletableFuture.supplyAsync(() -> {
            CachedProfileStatus fresh = profileCache.compute(key, (k, existing) -> {
                if (existing != null && existing.isValid()) return existing;
                boolean result = fetchIsProfilePublic(steamId, personalToken);
                return new CachedProfileStatus(result, Instant.now().plus(profileCacheTtl));
            });
            return fresh.isPublic();
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
        return CompletableFuture.supplyAsync(() -> fetchOwnedGameIds(steamId, personalToken), steamExecutor);
    }

    // -------------------------------------------------------------------------
    // Private HTTP helpers (synchronous, called from virtual threads)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Optional<PlayerSummary>> fetchPlayerBatch(List<String> steamIds) {
        Map<String, Optional<PlayerSummary>> result = steamIds.stream()
            .collect(Collectors.toMap(id -> id, id -> Optional.empty()));

        Optional<String> keyOpt = rotator.nextKey();
        if (keyOpt.isEmpty()) {
            log.warn("No Steam API key available, skipping batch of {} users", steamIds.size());
            return result;
        }
        String apiKey = keyOpt.get();

        if (!rateLimiter.acquire(apiKey)) {
            log.warn("Steam rate limit reached, skipping batch of {} users", steamIds.size());
            return result;
        }

        String url = UriComponentsBuilder
            .fromUriString(PLAYER_SUMMARIES_URL)
            .queryParam("key", apiKey)
            .queryParam("steamids", String.join(",", steamIds))
            .toUriString();

        try {
            Map<String, Object> response = restClient.get().uri(url).retrieve().body(Map.class);
            if (response == null) return result;

            Map<String, Object> body = (Map<String, Object>) response.get(RESPONSE_KEY);
            if (body == null) return result;

            List<Map<String, Object>> players = (List<Map<String, Object>>) body.get("players");
            if (players == null) return result;

            for (Map<String, Object> player : players) {
                String id = String.valueOf(player.get(STEAMID_KEY));
                Object gameId   = player.get("gameid");
                Object gameName = player.get("gameextrainfo");
                result.put(id,
                    (gameId != null && gameName != null)
                        ? Optional.of(new PlayerSummary(String.valueOf(gameId), String.valueOf(gameName)))
                        : Optional.empty());
            }
        } catch (HttpClientErrorException.TooManyRequests e) {
            int retryAfter = parseRetryAfter(e);
            rotator.onKeyRateLimited(apiKey, retryAfter);
            log.warn("Steam API 429 during batch fetch: retry after {}s", retryAfter);
        } catch (Exception e) {
            log.warn("Failed to batch-fetch Steam player summaries: {}", e.getMessage());
        }

        return result;
    }

    private boolean fetchIsProfilePublic(String steamId, String personalToken) {
        boolean visibilityPublic = fetchPlayer(steamId, personalToken)
            .map(player -> {
                Object visibility = player.get("communityvisibilitystate");
                return visibility != null && ((Number) visibility).intValue() == 3;
            })
            .orElse(false);
        if (!visibilityPublic) return false;
        return isGameListVisible(steamId, personalToken);
    }

    @SuppressWarnings("unchecked")
    private boolean isGameListVisible(String steamId, String personalToken) {
        String key;
        boolean usedRotator;
        if (personalToken != null && !personalToken.isBlank()) {
            key = personalToken;
            usedRotator = false;
        } else {
            Optional<String> keyOpt = rotator.nextKey();
            if (keyOpt.isEmpty()) {
                log.warn("No Steam API key available for game list visibility check {}", steamId);
                return false;
            }
            key = keyOpt.get();
            usedRotator = true;
        }

        if (!rateLimiter.acquire(key)) {
            log.warn("Steam rate limit reached, skipping game list visibility check for {}", steamId);
            return false;
        }

        String url = UriComponentsBuilder
            .fromUriString(OWNED_GAMES_URL)
            .queryParam("key", key)
            .queryParam("steamid", steamId)
            .toUriString();

        try {
            Map<String, Object> response = restClient.get()
                .uri(url)
                .retrieve()
                .body(Map.class);
            if (response == null) return false;

            Map<String, Object> responseBody = (Map<String, Object>) response.get(RESPONSE_KEY);
            return responseBody != null && responseBody.containsKey("game_count");
        } catch (HttpClientErrorException.TooManyRequests e) {
            int retryAfter = parseRetryAfter(e);
            if (usedRotator) rotator.onKeyRateLimited(key, retryAfter);
            else rateLimiter.onRateLimitResponse(key, retryAfter);
            log.warn("Steam API 429 for {}: retry after {}s", steamId, retryAfter);
            return false;
        } catch (Exception e) {
            log.warn("Failed to check Steam game list visibility for {}: {}", steamId, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> fetchPlayer(String steamId, String personalToken) {
        String token;
        boolean usedRotator;
        if (personalToken != null && !personalToken.isBlank()) {
            token = personalToken;
            usedRotator = false;
        } else {
            Optional<String> keyOpt = rotator.nextKey();
            if (keyOpt.isEmpty()) {
                log.warn("No Steam API key available for player fetch {}", steamId);
                return Optional.empty();
            }
            token = keyOpt.get();
            usedRotator = true;
        }

        if (!rateLimiter.acquire(token)) {
            log.warn("Steam rate limit reached, skipping player fetch for {}", steamId);
            return Optional.empty();
        }

        String url = UriComponentsBuilder
            .fromUriString(PLAYER_SUMMARIES_URL)
            .queryParam("key", token)
            .queryParam("steamids", steamId)
            .toUriString();

        try {
            Map<String, Object> response = restClient.get()
                .uri(url)
                .retrieve()
                .body(Map.class);
            if (response == null) return Optional.empty();

            Map<String, Object> responseBody = (Map<String, Object>) response.get(RESPONSE_KEY);
            if (responseBody == null) return Optional.empty();

            List<Map<String, Object>> players = (List<Map<String, Object>>) responseBody.get("players");
            if (players == null || players.isEmpty()) return Optional.empty();

            return Optional.of(players.getFirst());
        } catch (HttpClientErrorException.TooManyRequests e) {
            int retryAfter = parseRetryAfter(e);
            if (usedRotator) rotator.onKeyRateLimited(token, retryAfter);
            else rateLimiter.onRateLimitResponse(token, retryAfter);
            log.warn("Steam API 429 for {}: retry after {}s", steamId, retryAfter);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to fetch Steam player data for {}: {}", steamId, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> fetchOwnedGameIds(String steamId, String personalToken) {
        boolean usePersonalToken = personalToken != null && !personalToken.isBlank();

        while (true) {
            String key;
            boolean usedRotator;
            if (usePersonalToken) {
                key = personalToken;
                usedRotator = false;
            } else {
                Optional<String> keyOpt = rotator.nextKey();
                if (keyOpt.isEmpty()) {
                    log.warn("No Steam API key available for owned game fetch {}", steamId);
                    return List.of();
                }
                key = keyOpt.get();
                usedRotator = true;
            }

            if (!rateLimiter.acquireBlocking(key)) return List.of();

            String url = UriComponentsBuilder
                .fromUriString(OWNED_GAMES_URL)
                .queryParam("key", key)
                .queryParam("steamid", steamId)
                .queryParam("include_appinfo", 1)
                .toUriString();

            try {
                Map<String, Object> response = restClient.get().uri(url).retrieve().body(Map.class);
                if (response == null) return List.of();
                Map<String, Object> body = (Map<String, Object>) response.get(RESPONSE_KEY);
                if (body == null) return List.of();
                List<Map<String, Object>> games = (List<Map<String, Object>>) body.get("games");
                if (games == null) return List.of();
                return games.stream()
                    .map(g -> g.get("appid"))
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toList();
            } catch (HttpClientErrorException.TooManyRequests e) {
                int retryAfter = parseRetryAfter(e);
                if (usedRotator) rotator.onKeyRateLimited(key, retryAfter);
                else rateLimiter.onRateLimitResponse(key, retryAfter);
                log.warn("Steam API 429 fetching owned games for {}: retry after {}s", steamId, retryAfter);
                if (usePersonalToken || rotator.isAllKeysBlocked()) {
                    return List.of();
                }
            } catch (Exception e) {
                log.warn("Failed to fetch owned Steam games for {}: {}", steamId, e.getMessage());
                return List.of();
            }
        }
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

    private record CacheKey(String steamId, String tokenKey) {}

    private record CachedProfileStatus(boolean isPublic, Instant expiresAt) {
        boolean isValid() { return Instant.now().isBefore(expiresAt); }
    }
}
