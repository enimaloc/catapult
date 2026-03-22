package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.IgdbGameCacheEntry;
import fr.esportline.catapult.domain.TwitchCcl;
import fr.esportline.catapult.repository.IgdbGameCacheRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import proto.ExternalGameSource;
import proto.Game;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class IgdbService {

    private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String KEY_STEAM_PREFIX = "steam:";
    private static final String KEY_NAME_PREFIX  = "name:";

    private final IgdbClient igdbClient;
    private final IgdbGameCacheRepository cacheRepository;
    private final RestClient restClient;

    @Value("${app.igdb.client-id:}")
    private String clientId;

    @Value("${twitch.client-secret:}")
    private String clientSecret;

    @Value("${app.igdb.preload-ttl-hours:24}")
    private int preloadTtlHours;

    @Value("${app.igdb.cache-ttl-hours:24}")
    private int cacheTtlHours;

    @Value("${app.igdb.preload-limit:500}")
    private int preloadLimit;

    @Value("${app.mapping.ccl.violence:}")
    private String cclViolence;

    @Value("${app.mapping.ccl.mature:}")
    private String cclMature;

    @Value("${app.mapping.ccl.sexual_content:}")
    private String cclSexualContent;

    @Value("${app.mapping.ccl.drugs:}")
    private String cclDrugs;

    @Value("${app.mapping.ccl.gambling:}")
    private String cclGambling;

    @Value("${app.mapping.ccl.profanity:}")
    private String cclProfanity;

    @Value("${app.mapping.ccl.language_barrier:}")
    private String cclLanguageBarrier;

    // L1 cache: igdbId → name
    private final Map<String, String> igdbGameCache = new ConcurrentHashMap<>();

    // L1 index: normalized name → IgdbGame
    private final Map<String, IgdbGame> igdbNameIndex = new ConcurrentHashMap<>();

    // App-level Twitch token
    private volatile String appAccessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    // Steam ExternalGameSource ID (-1 = not resolved → fallback category=1)
    private volatile long steamSourceId = -1;

    @PostConstruct
    public void init() {
        loadSteamSourceId();
        warmInMemoryCacheFromDb();
        preloadGameList();
    }

    @Scheduled(fixedRateString = "${app.igdb.preload-ttl-hours:24}", timeUnit = TimeUnit.HOURS)
    public void preloadGameList() {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            log.warn("IGDB credentials not configured — skipping preload");
            return;
        }

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return;

        log.info("Preloading IGDB game list (limit={})...", preloadLimit);
        Map<String, String> newCache = new HashMap<>();
        Map<String, IgdbGame> newIndex = new HashMap<>();

        int pageSize = 500;
        int offset = 0;

        while (offset < preloadLimit) {
            int limit = Math.min(pageSize, preloadLimit - offset);
            List<Game> page = igdbClient.fetchGamePage(limit, offset, token);
            if (page.isEmpty()) break;

            for (Game game : page) {
                String id = String.valueOf(game.getId());
                newCache.put(id, game.getName());
                newIndex.put(normalise(game.getName()), new IgdbGame(id, game.getName()));
            }

            offset += page.size();
            if (page.size() < limit) break;
        }

        igdbGameCache.clear();
        igdbGameCache.putAll(newCache);
        igdbNameIndex.clear();
        igdbNameIndex.putAll(newIndex);

        log.info("IGDB preload complete: {} games cached", igdbGameCache.size());
    }

    public Map<String, String> getGameCache() {
        return Collections.unmodifiableMap(igdbGameCache);
    }

    public Optional<IgdbGame> findBySteamAppId(String appId) {
        if (clientId.isBlank()) return Optional.empty();

        String key = KEY_STEAM_PREFIX + appId;
        Optional<IgdbGame> fromDb = lookupInDb(key);
        if (fromDb.isPresent()) return fromDb;

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return Optional.empty();

        List<proto.ExternalGame> results = igdbClient.findExternalGamesByUid(appId, steamSourceId, token);
        if (results.isEmpty()) return Optional.empty();

        Game game = results.get(0).getGame();
        IgdbGame resolved = new IgdbGame(String.valueOf(game.getId()), game.getName());
        saveToDb(key, resolved);
        return Optional.of(resolved);
    }

    public Optional<IgdbGame> findByName(String gameName) {
        if (clientId.isBlank()) return Optional.empty();

        String normalized = normalise(gameName);
        IgdbGame cached = igdbNameIndex.get(normalized);
        if (cached != null) {
            log.debug("IGDB in-memory cache hit for '{}'", gameName);
            return Optional.of(cached);
        }

        String key = KEY_NAME_PREFIX + normalized;
        Optional<IgdbGame> fromDb = lookupInDb(key);
        if (fromDb.isPresent()) {
            igdbNameIndex.put(normalized, fromDb.get());
            return fromDb;
        }

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return Optional.empty();

        List<Game> results = igdbClient.searchByName(gameName, token);
        if (results.isEmpty()) return Optional.empty();

        Game game = results.get(0);
        IgdbGame resolved = new IgdbGame(String.valueOf(game.getId()), game.getName());
        igdbNameIndex.put(normalized, resolved);
        saveToDb(key, resolved);
        return Optional.of(resolved);
    }

    public Set<TwitchCcl> suggestCcls(String igdbGameId) {
        Set<TwitchCcl> suggested = new HashSet<>();
        if (clientId.isBlank()) return suggested;

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return suggested;

        List<Game> results = igdbClient.fetchGameById(
            igdbGameId, "themes.name,keywords.name,age_ratings.rating", token);
        if (results.isEmpty()) return suggested;

        Game game = results.get(0);
        Set<String> terms = new HashSet<>();
        game.getThemesList().forEach(t -> terms.add(t.getName().toLowerCase()));
        game.getKeywordsList().forEach(k -> terms.add(k.getName().toLowerCase()));

        mapTermToCcl(terms, cclViolence,        "violence",  TwitchCcl.ViolentGraphic,    suggested);
        mapTermToCcl(terms, cclMature,          "mature",    TwitchCcl.MatureGame,         suggested);
        mapTermToCcl(terms, cclSexualContent,   "sexual",    TwitchCcl.SexualThemes,       suggested);
        mapTermToCcl(terms, cclDrugs,           "drug",      TwitchCcl.DrugUse,            suggested);
        mapTermToCcl(terms, cclGambling,        "gambling",  TwitchCcl.Gambling,           suggested);
        mapTermToCcl(terms, cclProfanity,       "profanity", TwitchCcl.ProfanityVulgarity, suggested);
        mapTermToCcl(terms, cclLanguageBarrier, "language",  TwitchCcl.LanguageBarrier,    suggested);

        return suggested;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void loadSteamSourceId() {
        if (clientId.isBlank() || clientSecret.isBlank()) return;
        String token = getOrRefreshAppToken();
        if (token.isBlank()) return;

        try {
            List<ExternalGameSource> sources = igdbClient.findSourcesByName("Steam", token);
            if (!sources.isEmpty()) {
                steamSourceId = sources.get(0).getId();
                log.info("Steam ExternalGameSource ID resolved: {}", steamSourceId);
            } else {
                log.warn("Steam ExternalGameSource not found — falling back to category=1");
            }
        } catch (Exception e) {
            log.warn("Failed to resolve Steam ExternalGameSource ID: {} — falling back to category=1",
                e.getMessage());
        }
    }

    private void warmInMemoryCacheFromDb() {
        Instant since = Instant.now().minusSeconds(cacheTtlHours * 3600L);
        List<IgdbGameCacheEntry> entries = cacheRepository.findByCachedAtAfter(since);
        for (IgdbGameCacheEntry entry : entries) {
            IgdbGame game = new IgdbGame(entry.getIgdbId(), entry.getName());
            if (entry.getLookupKey().startsWith(KEY_NAME_PREFIX)) {
                String normalizedName = entry.getLookupKey().substring(KEY_NAME_PREFIX.length());
                igdbNameIndex.put(normalizedName, game);
            }
            igdbGameCache.putIfAbsent(entry.getIgdbId(), entry.getName());
        }
        log.info("IGDB DB cache warmed: {} entries loaded", entries.size());
    }

    private Optional<IgdbGame> lookupInDb(String key) {
        return cacheRepository.findById(key)
            .filter(e -> e.getCachedAt().isAfter(Instant.now().minusSeconds(cacheTtlHours * 3600L)))
            .map(e -> new IgdbGame(e.getIgdbId(), e.getName()));
    }

    private void saveToDb(String key, IgdbGame game) {
        cacheRepository.save(new IgdbGameCacheEntry(key, game.id(), game.name()));
    }

    @SuppressWarnings("unchecked")
    private synchronized String getOrRefreshAppToken() {
        if (appAccessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
            return appAccessToken;
        }
        try {
            Map<String, Object> response = restClient.post()
                .uri(TWITCH_TOKEN_URL + "?client_id=" + clientId
                     + "&client_secret=" + clientSecret
                     + "&grant_type=client_credentials")
                .retrieve()
                .body(Map.class);

            if (response == null) {
                log.error("IGDB app token request returned null");
                return "";
            }

            appAccessToken = (String) response.get("access_token");
            Number expiresIn = (Number) response.get("expires_in");
            tokenExpiresAt = expiresIn != null
                ? Instant.now().plusSeconds(expiresIn.longValue())
                : Instant.now().plusSeconds(3600);

            log.debug("IGDB app access token refreshed, expires in {}s", expiresIn);
            return appAccessToken != null ? appAccessToken : "";
        } catch (Exception e) {
            log.error("Failed to fetch IGDB app access token: {}", e.getMessage());
            return "";
        }
    }

    private static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void mapTermToCcl(Set<String> terms, String igdbKey, String keyword,
                               TwitchCcl ccl, Set<TwitchCcl> result) {
        if (!igdbKey.isBlank() && terms.stream().anyMatch(t -> t.contains(keyword))) {
            result.add(ccl);
        }
    }

    public record IgdbGame(String id, String name) {}
}
