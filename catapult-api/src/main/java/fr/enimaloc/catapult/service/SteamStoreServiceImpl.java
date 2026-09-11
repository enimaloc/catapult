package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.SteamAppParentEntry;
import fr.enimaloc.catapult.getter.SteamPlaytestRedirectResolver;
import fr.enimaloc.catapult.repository.SteamAppParentRepository;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mock.steam", havingValue = "false", matchIfMissing = true)
public class SteamStoreServiceImpl implements SteamStoreService {

    private static final String APP_DETAILS_URL = "https://store.steampowered.com/api/appdetails";

    private static final Map<String, Set<String>> KEYWORDS = Map.of(
        "ViolentGraphic",    Set.of("blood", "gore", "violence", "violent", "killing", "combat", "death", "injury"),
        "SexualThemes",      Set.of("nudity", "sexual", "sex", "suggestive", "erotic", "partial nudity"),
        "DrugsIntoxication", Set.of("drug", "alcohol", "tobacco", "substance", "intoxication"),
        "Gambling",          Set.of("gambling", "simulated gambling", "betting"),
        "ProfanityVulgarity",Set.of("language", "profanity", "crude", "bad language", "strong language", "lyrics")
    );

    private final RestClient restClient;
    private final ExternalApiObservations apiObservations;
    private final SteamAppParentRepository steamAppParentRepository;
    private final SteamPlaytestRedirectResolver redirectResolver;

    private static final java.time.Duration PARENT_CACHE_TTL = java.time.Duration.ofDays(30);

    @Override
    public Map<String, Set<String>> fetchCcls(Collection<String> appIds) {
        if (appIds.isEmpty()) return Map.of();
        return apiObservations.observe("steam_store", "fetch_ccls", () -> {
            String uri = appIds.stream()
                .collect(Collectors.joining("&appids=", APP_DETAILS_URL + "?appids=", ""));

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(Map.class);

                if (response == null) return Map.of();

                Map<String, Set<String>> result = new HashMap<>();
                for (String appId : appIds) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = (Map<String, Object>) response.get(appId);
                    if (entry == null || !Boolean.TRUE.equals(entry.get("success")) || entry.get("data") == null) continue;

                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) entry.get("data");

                    Set<String> ccls = extractCcls(data);
                    if (!ccls.isEmpty()) result.put(appId, ccls);
                }
                log.debug("Steam store fetch for {} appIds: {} had rating data", appIds.size(), result.size());
                return result;

            } catch (Exception e) {
                log.warn("Steam store appdetails failed for appIds={}: {}", appIds, e.getMessage());
                return Map.of();
            }
        });
    }

    @Override
    public Map<String, SteamTwSignals> fetchTwSignals(Collection<String> appIds) {
        if (appIds.isEmpty()) return Map.of();
        return apiObservations.observe("steam_store", "fetch_tw_signals", () -> {
            String uri = appIds.stream()
                .collect(Collectors.joining("&appids=", APP_DETAILS_URL + "?appids=", ""));

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(Map.class);

                if (response == null) return Map.of();

                Map<String, SteamTwSignals> result = new HashMap<>();
                for (String appId : appIds) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = (Map<String, Object>) response.get(appId);
                    if (entry == null || !Boolean.TRUE.equals(entry.get("success")) || entry.get("data") == null) continue;

                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) entry.get("data");
                    result.put(appId, extractTwSignals(data));
                }
                return result;

            } catch (Exception e) {
                log.warn("Steam fetchTwSignals failed for appIds={}: {}", appIds, e.getMessage());
                return Map.of();
            }
        });
    }

    /** Package-private for unit testing. */
    @SuppressWarnings("unchecked")
    static SteamTwSignals extractTwSignals(Map<String, Object> data) {
        Map<String, Object> cd = (Map<String, Object>) data.get("content_descriptors");
        if (cd == null) return SteamTwSignals.empty();
        Set<Integer> ids = new HashSet<>();
        Object rawIds = cd.get("ids");
        if (rawIds instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) ids.add(n.intValue());
            }
        }
        String notes = String.valueOf(cd.getOrDefault("notes", "")).toLowerCase(Locale.ROOT);
        return new SteamTwSignals(ids, notes);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<ResolvedParentApp> resolveEffectiveApp(String appId) {
        return apiObservations.observe("steam_store", "resolve_effective_app", () -> {
            Optional<SteamAppParentEntry> fresh = findCached(appId)
                .filter(e -> e.getResolvedAt().isAfter(Instant.now().minus(PARENT_CACHE_TTL)));
            if (fresh.isPresent()) {
                SteamAppParentEntry entry = fresh.get();
                return entry.getParentAppId() == null
                    ? Optional.<ResolvedParentApp>empty()
                    : Optional.of(new ResolvedParentApp(entry.getParentAppId(), entry.getParentName()));
            }

            Optional<Map<String, Object>> dataOpt = fetchAppDetailsData(appId);
            if (dataOpt.isEmpty()) return Optional.empty();
            Map<String, Object> data = dataOpt.get();

            Optional<ResolvedParentApp> viaFullGame = extractFullGame(data);
            if (viaFullGame.isPresent()) {
                saveCached(new SteamAppParentEntry(appId, viaFullGame.get().appId(), viaFullGame.get().name()));
                return viaFullGame;
            }

            boolean looksLikePlaytest = "game".equals(data.get("type"))
                && String.valueOf(data.get("name")).endsWith("Playtest");
            if (!looksLikePlaytest) {
                saveCached(new SteamAppParentEntry(appId, null, null));
                return Optional.empty();
            }

            Optional<String> parentId = redirectResolver.resolveParentAppId(appId);
            if (parentId.isEmpty()) return Optional.empty(); // uncertain — don't cache, allow retry

            Optional<Map<String, Object>> parentDataOpt = fetchAppDetailsData(parentId.get());
            String parentName = parentDataOpt
                .flatMap(d -> Optional.ofNullable(d.get("name")).map(String::valueOf))
                .orElse(parentId.get());
            ResolvedParentApp resolved = new ResolvedParentApp(parentId.get(), parentName);
            if (parentDataOpt.isEmpty()) {
                // parent appdetails fetch failed (network/rate-limit) — don't cache a placeholder name, allow retry
                return Optional.of(resolved);
            }
            saveCached(new SteamAppParentEntry(appId, resolved.appId(), resolved.name()));
            return Optional.of(resolved);
        });
    }

    /** DB-unavailable fallback: treat as a cache miss and resolve live rather than failing the call. */
    private Optional<SteamAppParentEntry> findCached(String appId) {
        try {
            return steamAppParentRepository.findById(appId);
        } catch (Exception e) {
            log.warn("steam_app_parent lookup failed for appId={}: {}", appId, e.getMessage());
            return Optional.empty();
        }
    }

    /** DB-unavailable fallback: a resolved result is still returned to the caller even if it can't be persisted. */
    private void saveCached(SteamAppParentEntry entry) {
        try {
            steamAppParentRepository.save(entry);
        } catch (Exception e) {
            log.warn("steam_app_parent save failed for appId={}: {}", entry.getAppId(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> fetchAppDetailsData(String appId) {
        try {
            Map<String, Object> response = restClient.get()
                .uri(APP_DETAILS_URL + "?appids=" + appId).retrieve().body(Map.class);
            if (response == null) return Optional.empty();
            Map<String, Object> entry = (Map<String, Object>) response.get(appId);
            if (entry == null || !Boolean.TRUE.equals(entry.get("success"))) return Optional.empty();
            return Optional.ofNullable((Map<String, Object>) entry.get("data"));
        } catch (Exception e) {
            log.warn("Steam appdetails failed for appId={}: {}", appId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> fetchDescription(String appId, Locale locale) {
        return apiObservations.observe("steam_store", "fetch_description", () -> {
            String effectiveAppId = resolveEffectiveApp(appId)
                    .map(ResolvedParentApp::appId)
                    .orElse(appId);
            String lang = SteamLanguages.fromLocale(locale);
            try {
                Map<String, Object> response = restClient.get()
                        .uri(APP_DETAILS_URL + "?appids=" + effectiveAppId + "&l=" + lang)
                        .retrieve()
                        .body(Map.class);
                if (response == null) return Optional.empty();
                Map<String, Object> entry = (Map<String, Object>) response.get(effectiveAppId);
                if (entry == null || !Boolean.TRUE.equals(entry.get("success"))) return Optional.empty();
                Map<String, Object> data = (Map<String, Object>) entry.get("data");
                if (data == null) return Optional.empty();
                String description = String.valueOf(data.getOrDefault("short_description", ""));
                return description.isBlank() ? Optional.empty() : Optional.of(description);
            } catch (Exception e) {
                log.warn("Steam fetchDescription failed for appId={}: {}", appId, e.getMessage());
                return Optional.empty();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Optional<ResolvedParentApp> extractFullGame(Map<String, Object> data) {
        Map<String, Object> fullgame = (Map<String, Object>) data.get("fullgame");
        if (fullgame == null || fullgame.get("appid") == null) return Optional.empty();
        String name = fullgame.get("name") != null ? String.valueOf(fullgame.get("name")) : String.valueOf(fullgame.get("appid"));
        return Optional.of(new ResolvedParentApp(String.valueOf(fullgame.get("appid")), name));
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractCcls(Map<String, Object> data) {
        Set<String> ccls = new HashSet<>();

        Map<String, Object> ratings = (Map<String, Object>) data.get("ratings");
        if (ratings == null) return ccls;

        for (Map.Entry<String, Object> entry : ratings.entrySet()) {
            Map<String, Object> rating = (Map<String, Object>) entry.getValue();

            String descriptors = String.valueOf(rating == null ? "" : rating.getOrDefault("descriptors", ""))
                .toLowerCase(Locale.ROOT);
            if (rating == null || descriptors.isBlank()) continue;

            KEYWORDS.forEach((cclId, keywords) -> {
                if (keywords.stream().anyMatch(descriptors::contains)) ccls.add(cclId);
            });
        }
        return ccls;
    }
}
