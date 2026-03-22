package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.IgdbGameCacheEntry;
import fr.esportline.catapult.domain.IgdbGameCcl;
import fr.esportline.catapult.domain.IgdbGameExternalId;
import fr.esportline.catapult.domain.TwitchCcl;
import fr.esportline.catapult.repository.IgdbGameCacheRepository;
import fr.esportline.catapult.repository.IgdbGameCclRepository;
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
    private final IgdbGameCclRepository cclRepository;
    private final RestClient restClient;

    @Value("${app.igdb.client-id:}")
    private String clientId;

    @Value("${twitch.client-secret:}")
    private String clientSecret;

    @Value("${app.igdb.cache-ttl-hours:24}")
    private int cacheTtlHours;

    // IGDB content_descriptions.description is a plain String (e.g. "Blood and Gore").
    // These keyword sets match substrings of that string (case-insensitive).
    private static final Set<String> DESC_VIOLENCE_KW  = Set.of("blood", "gore", "violence", "violent");
    private static final Set<String> DESC_SEXUAL_KW    = Set.of("nudity", "sexual", "sex", "suggestive", "erotic");
    private static final Set<String> DESC_DRUGS_KW     = Set.of("drug", "alcohol", "tobacco");
    private static final Set<String> DESC_GAMBLING_KW  = Set.of("gambling");
    private static final Set<String> DESC_LANGUAGE_KW  = Set.of("language", "lyrics", "profanity", "crude humor", "bad language");

    // L1 cache: igdbId → name
    private final Map<String, String> igdbGameCache = new ConcurrentHashMap<>();

    // L1 index: normalized name → IgdbGame
    private final Map<String, IgdbGame> igdbNameIndex = new ConcurrentHashMap<>();

    // CCL cache: igdbId → suggested CCLs (stable, no TTL needed)
    private final Map<String, Set<TwitchCcl>> cclCache = new ConcurrentHashMap<>();

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
        // L1: in-memory
        Set<TwitchCcl> cached = cclCache.get(igdbGameId);
        if (cached != null) {
            log.debug("CCL L1 cache hit for igdbId={}", igdbGameId);
            return cached;
        }
        // L2: DB
        var fromDb = cclRepository.findById(igdbGameId);
        if (fromDb.isPresent()) {
            Set<TwitchCcl> dbCcls = Collections.unmodifiableSet(fromDb.get().getCcls());
            cclCache.put(igdbGameId, dbCcls);
            log.debug("CCL L2 (DB) cache hit for igdbId={}", igdbGameId);
            return dbCcls;
        }

        Set<TwitchCcl> suggested = new HashSet<>();
        if (clientId.isBlank()) return suggested;

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return suggested;

        List<Game> results = igdbClient.fetchGameById(
            igdbGameId,
            "age_ratings.rating_category.organization.name,age_ratings.rating_category.rating," +
            "age_ratings.rating_content_descriptions.description",
            token);
        if (results.isEmpty()) return suggested;

        Game game = results.get(0);

        Set<String> ageRatingLabels = new LinkedHashSet<>();
        for (proto.AgeRating ar : game.getAgeRatingsList()) {
            proto.AgeRatingCategory rc = ar.getRatingCategory();
            String org    = rc.getOrganization().getName();
            String rating = rc.getRating();
            if (!org.isBlank() && !rating.isBlank()) {
                ageRatingLabels.add(org + " " + rating);
                String orgUc = org.toUpperCase(java.util.Locale.ROOT);
                // MatureGame: ESRB M / AO or PEGI 18
                if ((orgUc.contains("ESRB") && (rating.equals("M") || rating.equals("AO")))
                    || (orgUc.contains("PEGI") && rating.equals("18"))) {
                    suggested.add(TwitchCcl.MatureGame);
                }
            }
            for (proto.AgeRatingContentDescriptionV2 desc : ar.getRatingContentDescriptionsList()) {
                String d = desc.getDescription().toLowerCase(java.util.Locale.ROOT);
                if (DESC_VIOLENCE_KW.stream().anyMatch(d::contains))  suggested.add(TwitchCcl.ViolentGraphic);
                if (DESC_SEXUAL_KW.stream().anyMatch(d::contains))    suggested.add(TwitchCcl.SexualThemes);
                if (DESC_DRUGS_KW.stream().anyMatch(d::contains))     suggested.add(TwitchCcl.DrugUse);
                if (DESC_GAMBLING_KW.stream().anyMatch(d::contains))  suggested.add(TwitchCcl.Gambling);
                if (DESC_LANGUAGE_KW.stream().anyMatch(d::contains))  suggested.add(TwitchCcl.ProfanityVulgarity);
            }
        }

        String ageRatingsLabel = String.join(", ", ageRatingLabels);
        log.debug("IGDB CCL suggestion for {}: ratings={}, suggested={}", igdbGameId, ageRatingsLabel, suggested);
        Set<TwitchCcl> immutable = Collections.unmodifiableSet(suggested);
        cclCache.put(igdbGameId, immutable);
        cclRepository.save(new IgdbGameCcl(igdbGameId, suggested, ageRatingsLabel));
        return immutable;
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

    public record IgdbGame(String id, String name) {}
}
