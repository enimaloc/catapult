package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.IgdbGameCacheEntry;
import fr.esportline.catapult.domain.IgdbGameExternalId;
import fr.esportline.catapult.domain.TwitchCcl;
import fr.esportline.catapult.repository.IgdbGameCacheRepository;
import fr.esportline.catapult.repository.IgdbGameExternalIdRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import proto.ExternalGameSource;
import proto.Game;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@DependsOn("flyway")
@RequiredArgsConstructor
public class IgdbService {

    private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String KEY_STEAM_PREFIX = "steam:";
    private static final String KEY_NAME_PREFIX  = "name:";

    private final IgdbClient igdbClient;
    private final IgdbGameCacheRepository cacheRepository;
    private final IgdbGameExternalIdRepository externalIdRepository;
    private final RestClient restClient;

    @Value("${app.igdb.client-id:}")
    private String clientId;

    @Value("${twitch.client-secret:}")
    private String clientSecret;

    @Value("${app.igdb.cache-ttl-hours:24}")
    private int cacheTtlHours;

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

    // ExternalGameSource IDs (-1 = not resolved)
    private volatile long steamSourceId = -1;
    private volatile long twitchSourceId = -1;

    @PostConstruct
    public void init() {
        steamSourceId  = loadSourceId("Steam");
        twitchSourceId = loadSourceId("Twitch");
        warmInMemoryCacheFromDb();
    }

    public Map<String, String> getGameCache() {
        return Collections.unmodifiableMap(igdbGameCache);
    }

    public Optional<IgdbGame> findBySteamAppId(String appId) {
        if (clientId.isBlank()) return Optional.empty();

        // Check external ID table populated during preload
        if (steamSourceId >= 0) {
            Optional<IgdbGameExternalId> extId = externalIdRepository.findBySourceIdAndUid(steamSourceId, appId);
            if (extId.isPresent()) {
                String igdbId = extId.get().getIgdbId();
                String name = igdbGameCache.get(igdbId);
                if (name != null) {
                    log.debug("IGDB external ID cache hit for Steam appId={}", appId);
                    return Optional.of(new IgdbGame(igdbId, name));
                }
            }
        }

        // Legacy steam: key lookup in igdb_game_cache
        String key = KEY_STEAM_PREFIX + appId;
        Optional<IgdbGame> fromDb = lookupInDb(key);
        if (fromDb.isPresent()) return fromDb;

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return Optional.empty();

        List<proto.ExternalGame> results = igdbClient.findExternalGameByUid(appId, steamSourceId, token);
        if (results.isEmpty()) return Optional.empty();

        Game game = results.get(0).getGame();
        IgdbGame resolved = new IgdbGame(String.valueOf(game.getId()), game.getName());
        igdbGameCache.put(resolved.id(), resolved.name());
        igdbNameIndex.put(normalise(resolved.name()), resolved);
        saveToDb(key, resolved);
        return Optional.of(resolved);
    }

    /**
     * Pré-chauffe le cache pour une liste de Steam appIds en batch (max 500 par appel IGDB).
     * Les jeux déjà en cache DB ou mémoire sont ignorés.
     */
    public void prewarmSteamAppIds(List<String> appIds) {
        if (clientId.isBlank() || appIds.isEmpty()) return;

        // Filtrer ceux déjà en cache mémoire (via external ID table ou igdbGameCache)
        List<String> uncached = appIds.stream()
            .filter(id -> externalIdRepository.findBySourceIdAndUid(steamSourceId, id).isEmpty())
            .filter(id -> lookupInDb(KEY_STEAM_PREFIX + id).isEmpty())
            .toList();

        if (uncached.isEmpty()) {
            log.debug("All {} Steam appIds already cached", appIds.size());
            return;
        }

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return;

        int batchSize = 500;
        int resolved = 0;
        for (int i = 0; i < uncached.size(); i += batchSize) {
            List<String> chunk = uncached.subList(i, Math.min(i + batchSize, uncached.size()));
            List<proto.ExternalGame> results = igdbClient.findExternalGamesByUids(chunk, steamSourceId, token);
            for (proto.ExternalGame ext : results) {
                Game game = ext.getGame();
                IgdbGame igdbGame = new IgdbGame(String.valueOf(game.getId()), game.getName());
                igdbGameCache.put(igdbGame.id(), igdbGame.name());
                igdbNameIndex.put(normalise(igdbGame.name()), igdbGame);
                saveToDb(KEY_STEAM_PREFIX + ext.getUid(), igdbGame);
                resolved++;
            }
        }
        log.info("Steam batch prewarm: {}/{} resolved ({} already cached)",
            resolved, appIds.size(), appIds.size() - uncached.size());
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

    public Optional<String> findTwitchGameId(String igdbGameId) {
        if (twitchSourceId < 0) return Optional.empty();
        return externalIdRepository.findByIgdbIdAndSourceId(igdbGameId, twitchSourceId)
            .map(IgdbGameExternalId::getUid);
    }

    public Set<TwitchCcl> suggestCcls(String igdbGameId) {
        Set<TwitchCcl> suggested = new HashSet<>();
        if (clientId.isBlank()) return suggested;

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return suggested;

        List<Game> results = igdbClient.fetchGameById(
            igdbGameId, "themes.name,keywords.name,age_ratings.category,age_ratings.rating", token);
        if (results.isEmpty()) return suggested;

        Game game = results.get(0);

        // Age-rating based detection (primary signal)
        for (proto.AgeRating ar : game.getAgeRatingsList()) {
            int cat    = ar.getCategoryValue(); // 1=ESRB, 2=PEGI, 3=CERO, 4=USK
            int rating = ar.getRatingValue();
            // ESRB M (11) or AO (12)
            if (cat == 1 && (rating == 11 || rating == 12)) {
                if (!cclMature.isBlank())   suggested.add(TwitchCcl.MatureGame);
                if (!cclViolence.isBlank()) suggested.add(TwitchCcl.ViolentGraphic);
            }
            // ESRB AO only → also sexual
            if (cat == 1 && rating == 12 && !cclSexualContent.isBlank()) {
                suggested.add(TwitchCcl.SexualThemes);
            }
            // PEGI 18 (rating=5)
            if (cat == 2 && rating == 5) {
                if (!cclMature.isBlank())   suggested.add(TwitchCcl.MatureGame);
                if (!cclViolence.isBlank()) suggested.add(TwitchCcl.ViolentGraphic);
            }
        }

        // Keyword/theme based detection (supplementary)
        Set<String> terms = new HashSet<>();
        game.getThemesList().forEach(t -> terms.add(t.getName().toLowerCase()));
        game.getKeywordsList().forEach(k -> terms.add(k.getName().toLowerCase()));

        // Themes: "Erotic" → SexualThemes
        mapTermToCcl(terms, cclSexualContent, "erotic",    TwitchCcl.SexualThemes,       suggested);
        mapTermToCcl(terms, cclSexualContent, "sexual",    TwitchCcl.SexualThemes,       suggested);
        mapTermToCcl(terms, cclDrugs,         "drug",      TwitchCcl.DrugUse,            suggested);
        mapTermToCcl(terms, cclGambling,      "gambling",  TwitchCcl.Gambling,           suggested);
        mapTermToCcl(terms, cclProfanity,     "profanity", TwitchCcl.ProfanityVulgarity, suggested);
        mapTermToCcl(terms, cclProfanity,     "vulgar",    TwitchCcl.ProfanityVulgarity, suggested);
        mapTermToCcl(terms, cclLanguageBarrier, "language barrier", TwitchCcl.LanguageBarrier, suggested);

        log.debug("IGDB CCL suggestion for {}: terms={}, suggested={}", igdbGameId, terms, suggested);
        return suggested;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private long loadSourceId(String name) {
        if (clientId.isBlank() || clientSecret.isBlank()) return -1;
        String token = getOrRefreshAppToken();
        if (token.isBlank()) return -1;

        try {
            List<ExternalGameSource> sources = igdbClient.findSourcesByName(name, token);
            if (!sources.isEmpty()) {
                long id = sources.get(0).getId();
                log.info("{} ExternalGameSource ID resolved: {}", name, id);
                return id;
            } else {
                log.warn("{} ExternalGameSource not found", name);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve {} ExternalGameSource ID: {}", name, e.getMessage());
        }
        return -1;
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
    synchronized String getOrRefreshAppToken() {
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
