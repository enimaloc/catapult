package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.IgdbGameCacheEntry;
import fr.esportline.catapult.domain.IgdbGameCcl;
import fr.esportline.catapult.domain.IgdbGameExternalId;
import fr.esportline.catapult.domain.TwitchCcl;
import fr.esportline.catapult.domain.TwitchCclDefinition;
import fr.esportline.catapult.repository.IgdbGameCacheRepository;
import fr.esportline.catapult.repository.IgdbGameCclRepository;
import fr.esportline.catapult.repository.IgdbGameExternalIdRepository;
import fr.esportline.catapult.repository.TwitchCclDefinitionRepository;
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
import java.util.stream.Collectors;
import java.util.Objects;

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
    private final TwitchCclDefinitionRepository twitchCclRepo;
    private final SteamStoreService steamStoreService;
    private final RestClient restClient;

    @Value("${app.igdb.client-id:}")
    private String clientId;

    @Value("${twitch.client-secret:}")
    private String clientSecret;

    @Value("${app.igdb.cache-ttl-hours:24}")
    private int cacheTtlHours;

    // Keyword sets live in TwitchCcl (shared with SteamStoreService).

    private static final String CCL_FIELDS =
        "age_ratings.rating_content_descriptions.id," +
        "age_ratings.rating_content_descriptions.description";

    // L1 cache: igdbId → name
    private final Map<String, String> igdbGameCache = new ConcurrentHashMap<>();

    // L1 index: normalized name → IgdbGame
    private final Map<String, IgdbGame> igdbNameIndex = new ConcurrentHashMap<>();

    // CCL cache: igdbId → suggested CCLs (stable, no TTL needed)
    private final Map<String, Set<String>> cclCache = new ConcurrentHashMap<>();

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

    /**
     * Pré-chauffe le cache CCL pour tous les jeux déjà connus (igdb_game_cache) mais
     * pas encore dans igdb_game_ccl_cache. Utilise des appels batch IGDB (max 500 par requête).
     */
    public void prewarmCclCache() {
        if (clientId.isBlank()) return;

        Instant since = Instant.now().minusSeconds(cacheTtlHours * 3600L);
        Set<String> knownIds = cacheRepository.findByCachedAtAfter(since).stream()
            .map(IgdbGameCacheEntry::getIgdbId)
            .collect(Collectors.toSet());
        if (knownIds.isEmpty()) return;

        Set<String> alreadyCached = cclRepository.findAllIgdbIds();
        List<String> toLoad = knownIds.stream()
            .filter(id -> !alreadyCached.contains(id) && !cclCache.containsKey(id))
            .collect(Collectors.toList());

        if (toLoad.isEmpty()) {
            log.info("CCL cache already warm for all {} cached games", knownIds.size());
            return;
        }

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return;

        // Build igdbId → steamAppId map for the batch
        Map<String, String> igdbToSteam = new HashMap<>();
        if (steamSourceId >= 0) {
            externalIdRepository.findBySourceIdAndIgdbIdIn(steamSourceId, toLoad)
                .forEach(e -> igdbToSteam.put(e.getIgdbId(), e.getUid()));
        }

        log.info("CCL prewarm: loading {} / {} games ({} with Steam data)",
            toLoad.size(), knownIds.size(), igdbToSteam.size());

        int batchSize = 500;
        int resolved = 0;
        for (int i = 0; i < toLoad.size(); i += batchSize) {
            List<String> chunk = toLoad.subList(i, Math.min(i + batchSize, toLoad.size()));

            // Batch IGDB fetch
            List<Game> games = igdbClient.fetchGamesByIds(chunk, CCL_FIELDS, token);

            // Batch Steam fetch for the chunk
            List<String> steamIds = chunk.stream()
                .map(igdbToSteam::get).filter(Objects::nonNull).toList();
            Map<String, Set<TwitchCcl>> steamCcls = steamStoreService.fetchCcls(steamIds);

            for (Game game : games) {
                String igdbId   = String.valueOf(game.getId());
                String steamId  = igdbToSteam.get(igdbId);
                Set<TwitchCcl> fromSteam = steamId != null ? steamCcls.getOrDefault(steamId, Set.of()) : Set.of();
                storeCcls(igdbId, game, fromSteam);
                resolved++;
            }
        }
        log.info("CCL prewarm complete: {} / {} fetched", resolved, toLoad.size());
    }

    public Optional<String> findTwitchGameId(String igdbGameId) {
        if (twitchSourceId < 0) return Optional.empty();
        return externalIdRepository.findByIgdbIdAndSourceId(igdbGameId, twitchSourceId)
            .map(IgdbGameExternalId::getUid);
    }

    public Set<String> suggestCcls(String igdbGameId) {
        // L1: in-memory
        Set<String> cached = cclCache.get(igdbGameId);
        if (cached != null) {
            log.debug("CCL L1 cache hit for igdbId={}", igdbGameId);
            return cached;
        }
        // L2: DB
        var fromDb = cclRepository.findById(igdbGameId);
        if (fromDb.isPresent()) {
            Set<String> dbCcls = Collections.unmodifiableSet(
                fromDb.get().getCcls().stream().map(Enum::name).collect(Collectors.toSet())
            );
            cclCache.put(igdbGameId, dbCcls);
            log.debug("CCL L2 (DB) cache hit for igdbId={}", igdbGameId);
            return dbCcls;
        }

        if (clientId.isBlank()) return Set.of();
        String token = getOrRefreshAppToken();
        if (token.isBlank()) return Set.of();

        List<Game> results = igdbClient.fetchGameById(igdbGameId, CCL_FIELDS, token);
        if (results.isEmpty()) return Set.of();

        // Steam enrichment for single-game lookup
        Set<TwitchCcl> steamCcls = Set.of();
        if (steamSourceId >= 0) {
            Optional<String> steamAppId = externalIdRepository
                .findByIgdbIdAndSourceId(igdbGameId, steamSourceId)
                .map(IgdbGameExternalId::getUid);
            if (steamAppId.isPresent()) {
                steamCcls = steamStoreService.fetchCcls(List.of(steamAppId.get()))
                    .getOrDefault(steamAppId.get(), Set.of());
            }
        }

        return storeCcls(igdbGameId, results.get(0), steamCcls);
    }

    // -------------------------------------------------------------------------
    // CCL helpers
    // -------------------------------------------------------------------------

    private Set<String> storeCcls(String igdbGameId, Game game, Set<TwitchCcl> steamCcls) {
        Set<TwitchCcl> suggested = extractCcls(game);
        suggested.addAll(steamCcls);
        String ageRatingsLabel = extractAgeRatingsLabel(game);
        log.debug("IGDB+Steam CCL for {}: ratings={}, suggested={}", igdbGameId, ageRatingsLabel, suggested);
        cclRepository.save(new IgdbGameCcl(igdbGameId, suggested, ageRatingsLabel));
        Set<String> result = Collections.unmodifiableSet(
            suggested.stream().map(Enum::name).collect(Collectors.toSet())
        );
        cclCache.put(igdbGameId, result);
        return result;
    }

    private Set<TwitchCcl> extractCcls(Game game) {
        // Collect all descriptor IDs present on this game's age ratings
        Set<Long> descriptorIds = new HashSet<>();
        for (proto.AgeRating ar : game.getAgeRatingsList()) {
            for (proto.AgeRatingContentDescriptionV2 desc : ar.getRatingContentDescriptionsList()) {
                if (desc.getId() > 0) descriptorIds.add(desc.getId());
            }
        }

        if (descriptorIds.isEmpty()) return Set.of();

        // DB-driven: find which Twitch CCLs have any of these descriptors mapped
        Set<TwitchCcl> suggested = new HashSet<>();
        for (TwitchCclDefinition cclDef : twitchCclRepo.findAll()) {
            boolean matched = cclDef.getIgdbMappings().stream()
                .anyMatch(d -> descriptorIds.contains(d.getId()));
            if (matched) {
                try {
                    suggested.add(TwitchCcl.valueOf(cclDef.getId()));
                } catch (IllegalArgumentException ignored) {
                    // CCL from Twitch API not in local enum (e.g. future CCL) — skip
                }
            }
        }

        // Keyword fallback when no admin mappings have been configured yet
        if (suggested.isEmpty()) {
            for (proto.AgeRating ar : game.getAgeRatingsList()) {
                for (proto.AgeRatingContentDescriptionV2 desc : ar.getRatingContentDescriptionsList()) {
                    String d = desc.getDescription().toLowerCase(java.util.Locale.ROOT);
                    if (TwitchCcl.KW_VIOLENCE.stream().anyMatch(d::contains))  suggested.add(TwitchCcl.ViolentGraphic);
                    if (TwitchCcl.KW_SEXUAL.stream().anyMatch(d::contains))    suggested.add(TwitchCcl.SexualThemes);
                    if (TwitchCcl.KW_DRUGS.stream().anyMatch(d::contains))     suggested.add(TwitchCcl.DrugsIntoxication);
                    if (TwitchCcl.KW_GAMBLING.stream().anyMatch(d::contains))  suggested.add(TwitchCcl.Gambling);
                    if (TwitchCcl.KW_LANGUAGE.stream().anyMatch(d::contains))  suggested.add(TwitchCcl.ProfanityVulgarity);
                }
            }
        }

        return suggested;
    }

    private String extractAgeRatingsLabel(Game game) {
        Set<String> labels = new LinkedHashSet<>();
        for (proto.AgeRating ar : game.getAgeRatingsList()) {
            proto.AgeRatingCategory rc = ar.getRatingCategory();
            String org    = rc.getOrganization().getName();
            String rating = rc.getRating();
            if (!org.isBlank() && !rating.isBlank()) {
                labels.add(org + " " + rating);
            }
        }
        return String.join(", ", labels);
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
