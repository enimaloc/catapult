package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameCacheEntry;
import fr.enimaloc.catapult.domain.IgdbGameCcl;
import fr.enimaloc.catapult.domain.IgdbGameExternalId;
import fr.enimaloc.catapult.domain.TwitchCclDefinition;
import fr.enimaloc.catapult.repository.IgdbGameCacheRepository;
import fr.enimaloc.catapult.repository.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.IgdbGameExternalIdRepository;
import fr.enimaloc.catapult.repository.TwitchCclDefinitionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import proto.*;

import java.time.Instant;
import java.util.*;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@DependsOn("flyway")
@RequiredArgsConstructor
public class IgdbService {

    private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String KEY_STEAM_PREFIX = "steam:";
    private static final String KEY_NAME_PREFIX  = "name:";
    private static final String KEY_EXE_PREFIX   = "exe:";

    private final IgdbClient igdbClient;
    private final IgdbGameCacheRepository cacheRepository;
    private final IgdbGameExternalIdRepository externalIdRepository;
    private final IgdbGameCclRepository cclRepository;
    private final TwitchCclDefinitionRepository twitchCclRepo;
    private final SteamStoreService steamStoreService;
    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    @Value("${app.igdb.client-id:}")
    private String clientId;

    @Value("${twitch.client-secret:}")
    private String clientSecret;

    @Value("${app.igdb.cache-ttl-hours:24}")
    private int cacheTtlHours;

    // Keyword fallback used when no admin DB mappings are configured yet.
    private static final Map<String, Set<String>> FALLBACK_KEYWORDS = Map.of(
        "ViolentGraphic",    Set.of("blood", "gore", "violence", "violent", "killing", "combat", "death", "injury"),
        "SexualThemes",      Set.of("nudity", "sexual", "sex", "suggestive", "erotic", "partial nudity"),
        "DrugsIntoxication", Set.of("drug", "alcohol", "tobacco", "substance", "intoxication"),
        "Gambling",          Set.of("gambling", "simulated gambling", "betting"),
        "ProfanityVulgarity",Set.of("language", "profanity", "crude", "bad language", "strong language", "lyrics")
    );

    private static final String CCL_FIELDS =
        "age_ratings.rating_content_descriptions.id," +
        "age_ratings.rating_content_descriptions.description";

    // L1 cache: igdbId → name
    private final Map<String, String> igdbGameCache = new ConcurrentHashMap<>();

    // L1 index: normalized name → IgdbGame
    private final Map<String, IgdbGame> igdbNameIndex = new ConcurrentHashMap<>();

    // L1 index: lowercase exe name → IgdbGame (alternative_names lookup)
    private final Map<String, IgdbGame> exeNameIndex = new ConcurrentHashMap<>();

    // CCL cache: igdbId → suggested CCLs (stable, no TTL needed)
    private final Map<String, Set<String>> cclCache = new ConcurrentHashMap<>();

    // JSON serializer for descriptor ids persisted on IgdbGameCcl
    private final ObjectMapper objectMapper = new ObjectMapper();

    // App-level Twitch token
    private volatile String appAccessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    /** Expiration du token app Twitch/IGDB courant (EPOCH tant qu'aucun token obtenu). */
    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

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
                    meterRegistry.counter("catapult.igdb.cache.lookup", "method", "steam", "result", "hit").increment();
                    return Optional.of(new IgdbGame(igdbId, name));
                }
            }
        }

        // Legacy steam: key lookup in igdb_game_cache
        String key = KEY_STEAM_PREFIX + appId;
        Optional<IgdbGame> fromDb = lookupInDb(key);
        if (fromDb.isPresent()) {
            meterRegistry.counter("catapult.igdb.cache.lookup", "method", "steam", "result", "db_hit").increment();
            return fromDb;
        }

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return Optional.empty();

        List<proto.ExternalGame> results = igdbClient.findExternalGameByUid(appId, steamSourceId, token);
        meterRegistry.counter("catapult.igdb.cache.lookup", "method", "steam", "result", "miss").increment();
        if (results.isEmpty()) {
            // The app may be a beta build — resolve to its parent and retry once.
            Optional<String> parentId = steamStoreService.resolveFullGameAppId(appId);
            if (parentId.isPresent()) {
                log.debug("Steam appId={} is a beta, retrying IGDB lookup with parentId={}", appId, parentId.get());
                return findBySteamAppId(parentId.get());
            }
            return Optional.empty();
        }

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
            meterRegistry.counter("catapult.igdb.cache.lookup", "method", "name", "result", "hit").increment();
            return Optional.of(cached);
        }

        String key = KEY_NAME_PREFIX + normalized;
        Optional<IgdbGame> fromDb = lookupInDb(key);
        if (fromDb.isPresent()) {
            meterRegistry.counter("catapult.igdb.cache.lookup", "method", "name", "result", "db_hit").increment();
            igdbNameIndex.put(normalized, fromDb.get());
            return fromDb;
        }

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return Optional.empty();

        List<Game> results = igdbClient.searchByName(gameName, token);
        meterRegistry.counter("catapult.igdb.cache.lookup", "method", "name", "result", "miss").increment();
        if (results.isEmpty()) return Optional.empty();

        Game game = results.get(0);
        IgdbGame resolved = new IgdbGame(String.valueOf(game.getId()), game.getName());
        igdbNameIndex.put(normalized, resolved);
        saveToDb(key, resolved);
        return Optional.of(resolved);
    }

    public Optional<IgdbGame> findByWindowsExecutable(String processName) {
        if (clientId.isBlank()) return Optional.empty();

        String exeName = processName.strip().toLowerCase(java.util.Locale.ROOT);
        if (!exeName.endsWith(".exe")) {
            exeName = exeName + ".exe";
        }

        IgdbGame cached = exeNameIndex.get(exeName);
        if (cached != null) {
            log.debug("IGDB exe L1 cache hit for '{}'", exeName);
            meterRegistry.counter("catapult.igdb.cache.lookup", "method", "exe", "result", "hit").increment();
            return Optional.of(cached);
        }

        String key = KEY_EXE_PREFIX + exeName;
        Optional<IgdbGame> fromDb = lookupInDb(key);
        if (fromDb.isPresent()) {
            meterRegistry.counter("catapult.igdb.cache.lookup", "method", "exe", "result", "db_hit").increment();
            exeNameIndex.put(exeName, fromDb.get());
            return fromDb;
        }

        String token = getOrRefreshAppToken();
        if (token.isBlank()) return Optional.empty();

        List<AlternativeName> results = igdbClient.findByWindowsExecutable(exeName, token);
        meterRegistry.counter("catapult.igdb.cache.lookup", "method", "exe", "result", "miss").increment();
        if (results.isEmpty()) return Optional.empty();

        proto.Game game = results.get(0).getGame();
        IgdbGame resolved = new IgdbGame(String.valueOf(game.getId()), game.getName());
        exeNameIndex.put(exeName, resolved);
        igdbGameCache.put(resolved.id(), resolved.name());
        igdbNameIndex.put(normalise(resolved.name()), resolved);
        saveToDb(key, resolved);
        return Optional.of(resolved);
    }

    public List<IgdbGame> searchGames(String query) {
        String trimmed = query.strip();
        if (clientId.isBlank() || trimmed.length() < 2) return List.of();
        String token = getOrRefreshAppToken();
        if (token.isBlank()) return List.of();
        return igdbClient.searchByName(trimmed, token).stream()
            .map(g -> new IgdbGame(String.valueOf(g.getId()), g.getName()))
            .toList();
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
            .toList();

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
            Map<String, Set<String>> steamCcls = steamStoreService.fetchCcls(steamIds);

            for (Game game : games) {
                String igdbId   = String.valueOf(game.getId());
                String steamId  = igdbToSteam.get(igdbId);
                Set<String> fromSteam = steamId != null ? steamCcls.getOrDefault(steamId, Set.of()) : Set.of();
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
            Set<String> dbCcls = Collections.unmodifiableSet(fromDb.get().getCcls());
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
        Set<String> steamCcls = Set.of();
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

    private Set<String> storeCcls(String igdbGameId, Game game, Set<String> steamCcls) {
        Set<String> suggested = new HashSet<>(extractCcls(game));
        suggested.addAll(steamCcls);
        String ageRatingsLabel = extractAgeRatingsLabel(game);

        Set<Long> descriptorIds = game.getAgeRatingsList().stream()
            .map(AgeRating::getRatingContentDescriptionsList)
            .flatMap(Collection::stream)
            .map(AgeRatingContentDescriptionV2::getId)
            .filter(id -> id > 0)
            .collect(Collectors.toSet());

        log.debug("IGDB+Steam CCL for {}: ratings={}, suggested={}, descriptorIds={}",
            igdbGameId, ageRatingsLabel, suggested, descriptorIds);
        IgdbGameCcl entity = new IgdbGameCcl(igdbGameId, suggested, ageRatingsLabel);
        try {
            entity.setDescriptorIdsJson(objectMapper.writeValueAsString(descriptorIds));
        } catch (Exception ex) {
            log.warn("Cannot serialize descriptor ids for igdbId={}: {}", igdbGameId, ex.getMessage());
        }
        cclRepository.save(entity);
        Set<String> result = Collections.unmodifiableSet(suggested);
        cclCache.put(igdbGameId, result);
        return result;
    }

    /**
     * Returns the IGDB age-rating descriptor ids contributing to a game's CCLs.
     * Reads from the cached {@link IgdbGameCcl#getDescriptorIdsJson()} when present,
     * otherwise re-fetches from IGDB. Returns an empty set on any failure.
     */
    public Set<Long> fetchDescriptorIds(String igdbGameId) {
        Optional<IgdbGameCcl> cached = cclRepository.findById(igdbGameId);
        if (cached.isPresent() && cached.get().getDescriptorIdsJson() != null) {
            try {
                return objectMapper.readValue(
                    cached.get().getDescriptorIdsJson(),
                    new TypeReference<Set<Long>>() {});
            } catch (Exception e) {
                log.warn("Bad descriptor_ids JSON for igdbId={}: {}", igdbGameId, e.getMessage());
            }
        }

        if (clientId.isBlank()) return Set.of();
        String token = getOrRefreshAppToken();
        if (token.isBlank()) return Set.of();

        try {
            List<Game> results = igdbClient.fetchGameById(igdbGameId, CCL_FIELDS, token);
            if (results.isEmpty()) return Set.of();
            return results.get(0).getAgeRatingsList().stream()
                .map(AgeRating::getRatingContentDescriptionsList)
                .flatMap(Collection::stream)
                .map(AgeRatingContentDescriptionV2::getId)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("fetchDescriptorIds failed for igdbId={}: {}", igdbGameId, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Re-applies the same IGDB resolution logic as BindingService: for STEAM bindings with a
     * non-null sourceId, try Steam appId lookup first and fall back to name search.
     * Returns the resolved {@link IgdbGame#id()} or empty if none found.
     */
    public Optional<String> resolveIgdbIdForBinding(GameBinding b) {
        if (b.getSourceType() == GameBinding.SourceType.STEAM && b.getSourceId() != null) {
            Optional<IgdbGame> byApp = findBySteamAppId(b.getSourceId());
            if (byApp.isPresent()) return byApp.map(IgdbGame::id);
        }
        return findByName(b.getSourceName()).map(IgdbGame::id);
    }

    private Set<String> extractCcls(Game game) {
        // Collect all descriptor IDs present on this game's age ratings
        Set<Long> descriptorIds = game.getAgeRatingsList()
            .stream()
            .map(AgeRating::getRatingContentDescriptionsList)
            .flatMap(Collection::stream)
            .map(AgeRatingContentDescriptionV2::getId)
            .filter(id -> id > 0)
            .collect(Collectors.toSet());

        if (descriptorIds.isEmpty()) return Set.of();

        // DB-driven: find which Twitch CCLs have any of these descriptors mapped
        Set<String> suggested = twitchCclRepo.findAll()
            .stream()
            .filter(def -> def.getIgdbMappings().stream().anyMatch(m -> descriptorIds.contains(m.getId())))
            .map(TwitchCclDefinition::getId)
            .collect(Collectors.toSet());

        // Keyword fallback when no admin mappings have been configured yet
        if (suggested.isEmpty()) {
            for (proto.AgeRating ar : game.getAgeRatingsList()) {
                for (proto.AgeRatingContentDescriptionV2 desc : ar.getRatingContentDescriptionsList()) {
                    String d = desc.getDescription().toLowerCase(java.util.Locale.ROOT);
                    FALLBACK_KEYWORDS.forEach((cclId, keywords) -> {
                        if (keywords.stream().anyMatch(d::contains)) suggested.add(cclId);
                    });
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

    public String getAppToken() {
        return getOrRefreshAppToken();
    }

    public long getTwitchSourceId() {
        return twitchSourceId;
    }

    private static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public record IgdbGame(String id, String name) {}
}
