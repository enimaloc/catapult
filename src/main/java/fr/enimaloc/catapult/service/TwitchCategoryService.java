package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.TwitchCategoryCache;
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
            case TOP   -> prewarmByTopGames();
            case SWEEP -> prewarmBySweep();
            case BOTH  -> { prewarmByTopGames(); prewarmBySweep(); }
            case NONE  -> log.info("Twitch category prewarm disabled (prewarm-mode=NONE)");
        }
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

    private void prewarmBySweep() {
        // implemented in Task 2
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
        if (token.isBlank() || twitchClientId.isBlank()) return List.of();

        try {
            Map<String, Object> response = restClient.get()
                    .uri(TWITCH_API_URL + "/search/categories?query="
                         + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                         + "&first=" + AUTOCOMPLETE_MAX)
                    .header("Authorization", "Bearer " + token)
                    .header("Client-Id", twitchClientId)
                    .retrieve()
                    .body(Map.class);

            if (response == null) return List.of();

            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) return List.of();

            List<TwitchCategoryCache> toStore = data.stream()
                    .map(g -> new TwitchCategoryCache(
                            (String) g.get("id"),
                            (String) g.get("name"),
                            (String) g.get("box_art_url")))
                    .collect(Collectors.toList());
            cacheRepo.saveAll(toStore);

            return toStore.stream()
                    .map(e -> new TwitchService.TwitchCategory(e.getId(), e.getName(), e.getBoxArtUrl()))
                    .toList();

        } catch (Exception e) {
            log.warn("Twitch category live search failed for '{}': {}", query, e.getMessage());
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
        if (twitchClientId.isBlank() || twitchClientSecret.isBlank()) return "";
        try {
            Map<String, Object> resp = restClient.post()
                    .uri(TWITCH_TOKEN_URL + "?client_id=" + twitchClientId
                         + "&client_secret=" + twitchClientSecret
                         + "&grant_type=client_credentials")
                    .retrieve()
                    .body(Map.class);

            if (resp == null) return "";
            appToken = (String) resp.get("access_token");
            Number expiresIn = (Number) resp.get("expires_in");
            tokenExpiresAt = expiresIn != null
                    ? Instant.now().plusSeconds(expiresIn.longValue())
                    : Instant.now().plusSeconds(3600);
            return appToken != null ? appToken : "";
        } catch (Exception e) {
            log.error("Failed to fetch Twitch app token: {}", e.getMessage());
            return "";
        }
    }
}
