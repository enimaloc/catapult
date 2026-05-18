package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.IgdbGameExternalId;
import fr.enimaloc.catapult.domain.TwitchCategoryCache;
import fr.enimaloc.catapult.repository.IgdbGameExternalIdRepository;
import fr.enimaloc.catapult.repository.TwitchCategoryCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@DependsOn("flyway")
@RequiredArgsConstructor
public class TwitchCategoryService {

    private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String TWITCH_API_URL   = "https://api.twitch.tv/helix";
    private static final int    AUTOCOMPLETE_MAX = 8;
    private static final int    BATCH_SIZE       = 100;

    public enum PrewarmMode { TOP, SWEEP, BOTH, NONE }

    private final TwitchCategoryCacheRepository cacheRepo;
    private final IgdbGameExternalIdRepository  externalIdRepo;
    private final IgdbService                   igdbService;
    private final RestClient restClient;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    @Value("${twitch.client-secret:}")
    private String twitchClientSecret;

    @Value("${app.twitch.category-cache-ttl-hours:24}")
    private int cacheTtlHours;

    @Value("${app.twitch.prewarm-mode:BOTH}")
    private PrewarmMode prewarmMode;

    private volatile String appToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    // -----------------------------------------------------------------------
    // Startup prewarm
    // -----------------------------------------------------------------------

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void prewarmCategoryCache() {
        switch (prewarmMode) {
            case TOP   -> { fillIgdbKnownCategories(); prewarmByTopGames(); }
            case SWEEP -> { fillIgdbKnownCategories(); prewarmBySweep(); }
            case BOTH  -> { fillIgdbKnownCategories(); prewarmByTopGames(); prewarmBySweep(); }
            case NONE  -> log.info("Twitch category prewarm disabled (prewarm-mode=NONE)");
        }
    }

    // Phase 1: fetch Twitch categories already known via IGDB but missing from cache
    @SuppressWarnings("unchecked")
    private void fillIgdbKnownCategories() {
        String token = getOrRefreshAppToken();
        if (token.isBlank() || twitchClientId.isBlank()) {
            log.info("IGDB cross-fill skipped — Twitch credentials not configured");
            return;
        }
        long srcId = igdbService.getTwitchSourceId();
        if (srcId < 0) {
            log.info("IGDB cross-fill skipped — Twitch source ID not resolved");
            return;
        }

        List<String> twitchIdsFromIgdb = externalIdRepo.findBySourceId(srcId).stream()
                .map(IgdbGameExternalId::getUid)
                .toList();

        if (twitchIdsFromIgdb.isEmpty()) return;

        Set<String> alreadyCached = cacheRepo.findAllById(twitchIdsFromIgdb).stream()
                .map(TwitchCategoryCache::getId)
                .collect(Collectors.toSet());

        List<String> missing = twitchIdsFromIgdb.stream()
                .filter(id -> !alreadyCached.contains(id))
                .toList();

        if (missing.isEmpty()) {
            log.info("IGDB cross-fill: all {} Twitch IDs already cached", twitchIdsFromIgdb.size());
            return;
        }

        log.info("IGDB cross-fill: fetching {} missing categories ({} already cached)",
                missing.size(), alreadyCached.size());

        int filled = 0;
        for (int i = 0; i < missing.size(); i += BATCH_SIZE) {
            List<String> chunk = missing.subList(i, Math.min(i + BATCH_SIZE, missing.size()));
            filled += fetchAndStoreByIds(chunk, token);
        }

        log.info("IGDB cross-fill complete: {}/{} categories fetched", filled, missing.size());
    }

    @SuppressWarnings("unchecked")
    private void prewarmByTopGames() {
        String token = getOrRefreshAppToken();
        if (token.isBlank() || twitchClientId.isBlank()) {
            log.info("Twitch category prewarm skipped — client credentials not configured");
            return;
        }

        log.info("Twitch category prewarm started (/helix/games/top pagination)");
        String cursor = null;
        int total = 0;

        do {
            String uri = TWITCH_API_URL + "/games/top?first=" + BATCH_SIZE
                    + (cursor != null ? "&after=" + cursor : "");
            cursor = null;

            try {
                Map<String, Object> response = restClient.get()
                        .uri(uri)
                        .header("Authorization", "Bearer " + token)
                        .header("Client-Id", twitchClientId)
                        .retrieve()
                        .body(Map.class);

                if (response == null) break;
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                if (data == null || data.isEmpty()) break;

                List<TwitchCategoryCache> batch = data.stream()
                        .map(g -> new TwitchCategoryCache(
                                (String) g.get("id"),
                                (String) g.get("name"),
                                (String) g.get("box_art_url")))
                        .collect(Collectors.toList());
                cacheRepo.saveAll(batch);
                log.trace("Warm with: {}", batch.stream().map(cat -> "%s (%s)".formatted(cat.getName(), cat.getId())).collect(Collectors.joining(", ", "[", "]")));
                total += batch.size();

                Map<String, Object> pagination = (Map<String, Object>) response.get("pagination");
                if (pagination != null) {
                    cursor = (String) pagination.get("cursor");
                }
            } catch (Exception e) {
                log.warn("Twitch category prewarm batch failed: {}", e.getMessage());
                break;
            }
        } while (cursor != null);

        log.info("Twitch category prewarm complete: {} categories cached", total);
    }

    @SuppressWarnings("unchecked")
    private void prewarmBySweep() {
        String token = getOrRefreshAppToken();
        if (token.isBlank() || twitchClientId.isBlank()) {
            log.info("Twitch category sweep skipped — client credentials not configured");
            return;
        }

        long maxKnown = cacheRepo.findMaxNumericId();
        long offset   = maxKnown + 1;
        int  total    = 0;

        log.info("Twitch category sweep started from ID {} (max cached: {})", offset, maxKnown);

        // Twitch omits missing IDs from the response rather than returning an error.
        // The sweep terminates on the first batch that yields zero results, meaning
        // 100 consecutive IDs were all unknown — a reliable end-of-range signal.
        while (true) {
            StringBuilder uri = new StringBuilder(TWITCH_API_URL + "/games");
            for (long i = offset; i < offset + BATCH_SIZE; i++) {
                uri.append(i == offset ? "?id=" : "&id=").append(i);
            }

            try {
                Map<String, Object> response = restClient.get()
                        .uri(uri.toString())
                        .header("Authorization", "Bearer " + token)
                        .header("Client-Id", twitchClientId)
                        .retrieve()
                        .body(Map.class);

                if (response == null) break;
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                if (data == null || data.isEmpty()) break;

                List<TwitchCategoryCache> batch = data.stream()
                        .map(g -> {
                            TwitchCategoryCache entry = new TwitchCategoryCache(
                                    (String) g.get("id"),
                                    (String) g.get("name"),
                                    (String) g.get("box_art_url"));
                            entry.setIgdbId((String) g.get("igdb_id"));
                            return entry;
                        })
                        .collect(Collectors.toList());
                cacheRepo.saveAll(batch);
                log.trace("Sweep batch [{}-{}]: {}", offset, offset + BATCH_SIZE - 1,
                        batch.stream().map(c -> "%s (%s)".formatted(c.getName(), c.getId()))
                             .collect(Collectors.joining(", ", "[", "]")));
                total += batch.size();
            } catch (Exception e) {
                log.warn("Twitch category sweep batch [{}-{}] failed: {}", offset, offset + BATCH_SIZE - 1, e.getMessage());
                break;
            }

            offset += BATCH_SIZE;
        }

        log.info("Twitch category sweep complete: {} categories cached", total);
    }

    // -----------------------------------------------------------------------
    // Shared fetch helper
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private int fetchAndStoreByIds(List<String> ids, String token) {
        StringBuilder uri = new StringBuilder(TWITCH_API_URL + "/games");
        for (int i = 0; i < ids.size(); i++) {
            uri.append(i == 0 ? "?id=" : "&id=").append(ids.get(i));
        }
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uri.toString())
                    .header("Authorization", "Bearer " + token)
                    .header("Client-Id", twitchClientId)
                    .retrieve()
                    .body(Map.class);
            if (response == null) return 0;
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) return 0;
            List<TwitchCategoryCache> batch = data.stream()
                    .map(g -> {
                        TwitchCategoryCache entry = new TwitchCategoryCache(
                                (String) g.get("id"),
                                (String) g.get("name"),
                                (String) g.get("box_art_url"));
                        entry.setIgdbId((String) g.get("igdb_id"));
                        return entry;
                    })
                    .collect(Collectors.toList());
            cacheRepo.saveAll(batch);
            return batch.size();
        } catch (Exception e) {
            log.warn("Twitch batch fetch failed for {} IDs: {}", ids.size(), e.getMessage());
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Autocomplete search
    // -----------------------------------------------------------------------

    public List<TwitchService.TwitchCategory> searchCategories(String query) {
        if (query == null || query.isBlank()) return List.of();

        Instant cutoff = Instant.now().minusSeconds(cacheTtlHours * 3600L);
        List<TwitchCategoryCache> fromDb = cacheRepo.findByNameContainingIgnoreCaseAndCachedAtAfter(
                query, cutoff, PageRequest.of(0, AUTOCOMPLETE_MAX));

        if (!fromDb.isEmpty()) {
            log.debug("Twitch category cache hit for '{}'", query);
            return fromDb.stream()
                    .map(e -> new TwitchService.TwitchCategory(e.getId(), e.getName(), e.getBoxArtUrl()))
                    .toList();
        }

        return fetchFromSearchAndStore(query);
    }

    // -----------------------------------------------------------------------
    // Live search fallback
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<TwitchService.TwitchCategory> fetchFromSearchAndStore(String query) {
        String token = getOrRefreshAppToken();
        if (token.isBlank() || twitchClientId.isBlank()) {
            log.warn("searchCategories '{}' — live search skipped: token={} clientId={}",
                    query, token.isBlank() ? "BLANK" : "ok", twitchClientId.isBlank() ? "BLANK" : "ok");
            return List.of();
        }

        log.debug("searchCategories '{}' — calling /search/categories (cache miss)", query);
        try {
            Map<String, Object> response = restClient.get()
                    .uri(TWITCH_API_URL + "/search/categories?query={query}&first={first}",
                         query, AUTOCOMPLETE_MAX)
                    .header("Authorization", "Bearer " + token)
                    .header("Client-Id", twitchClientId)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                log.warn("searchCategories '{}' — API returned null response", query);
                return List.of();
            }

            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) {
                log.warn("searchCategories '{}' — API returned empty data", query);
                return List.of();
            }

            List<TwitchCategoryCache> toStore = data.stream()
                    .map(g -> new TwitchCategoryCache(
                            (String) g.get("id"),
                            (String) g.get("name"),
                            (String) g.get("box_art_url")))
                    .collect(Collectors.toList());
            cacheRepo.saveAll(toStore);

            log.debug("searchCategories '{}' — stored {} results", query, toStore.size());
            return toStore.stream()
                    .map(e -> new TwitchService.TwitchCategory(e.getId(), e.getName(), e.getBoxArtUrl()))
                    .toList();

        } catch (Exception e) {
            log.warn("searchCategories '{}' — live search failed: {}", query, e.getMessage());
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // App token management
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    synchronized String getOrRefreshAppToken() {
        if (appToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
            return appToken;
        }
        if (twitchClientId.isBlank() || twitchClientSecret.isBlank()) {
            log.warn("getOrRefreshAppToken — credentials not configured (clientId={} secret={})",
                    twitchClientId.isBlank() ? "BLANK" : "ok",
                    twitchClientSecret.isBlank() ? "BLANK" : "ok");
            return "";
        }
        log.debug("getOrRefreshAppToken — fetching new app token");
        try {
            Map<String, Object> resp = restClient.post()
                    .uri(TWITCH_TOKEN_URL + "?client_id=" + twitchClientId
                         + "&client_secret=" + twitchClientSecret
                         + "&grant_type=client_credentials")
                    .retrieve()
                    .body(Map.class);

            if (resp == null) {
                log.warn("getOrRefreshAppToken — token endpoint returned null");
                return "";
            }
            appToken = (String) resp.get("access_token");
            Number expiresIn = (Number) resp.get("expires_in");
            tokenExpiresAt = expiresIn != null
                    ? Instant.now().plusSeconds(expiresIn.longValue())
                    : Instant.now().plusSeconds(3600);
            log.debug("getOrRefreshAppToken — token obtained, expires in {}s", expiresIn);
            return appToken != null ? appToken : "";
        } catch (Exception e) {
            log.error("getOrRefreshAppToken — failed to fetch app token: {}", e.getMessage());
            return "";
        }
    }
}
