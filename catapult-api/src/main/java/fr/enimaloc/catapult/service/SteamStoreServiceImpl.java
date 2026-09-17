package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonValue;
import fr.enimaloc.catapult.domain.SteamAppParentEntry;
import fr.enimaloc.catapult.getter.SteamPlaytestRedirectResolver;
import fr.enimaloc.catapult.repository.SteamAppParentRepository;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.*;

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
            try {
                Map<String, Set<String>> result = new HashMap<>();
                for (String appId : appIds) {
                    Optional<SteamStorePage> pageOpt = fetchData(appId, Locale.ENGLISH);
                    if (pageOpt.isEmpty()) return Map.of();
                    SteamStorePage page = pageOpt.get();
                    result.put(appId, extractCcls(page));
                }
                log.debug("Steam store fetch for {} appIds: {} had rating data", appIds.size(), result.size());
                return result;

            } catch (Exception e) {
                log.warn("Steam store appdetails failed for appIds={}: {}", appIds, e.getMessage());
                return Map.of();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractCcls(SteamStorePage data) {
        Set<String> ccls = new HashSet<>();
        SteamStorePage.Ratings ratings = data.ratings();

        if (ratings == null) return ccls;
        for (SteamStorePage.Ratings.Rating entry : ratings.entries()) {
            KEYWORDS.forEach((id, keyword) -> {
                if (keyword.stream().anyMatch(entry.descriptors()::contains)) ccls.add(id);
            });
        }

        return ccls;
    }

    @Override
    public Map<String, SteamTwSignals> fetchTwSignals(Collection<String> appIds) {
        if (appIds.isEmpty()) return Map.of();
        return apiObservations.observe("steam_store", "fetch_tw_signals", () -> {
            try {
                Map<String, SteamTwSignals> result = new HashMap<>();
                for (String appId : appIds) {
                    Optional<SteamStorePage> pageOpt = fetchData(appId, Locale.ENGLISH);
                    if (pageOpt.isEmpty()) return Map.of();
                    SteamStorePage page = pageOpt.get();
                    result.put(appId, extractTwSignals(page));
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
    static SteamTwSignals extractTwSignals(SteamStorePage data) {
        SteamStorePage.ContentDescriptors cd = data.contentDescriptors();
        if (cd == null) return SteamTwSignals.empty();
        Set<Integer> ids = new HashSet<>();
        for (int id : cd.ids()) ids.add(id);
        String notes = (cd.note() != null ? cd.note() : "").toLowerCase(Locale.ROOT);
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

            Optional<SteamStorePage> dataOpt = fetchData(appId, Locale.ENGLISH, false);
            if (dataOpt.isEmpty()) return Optional.empty();
            SteamStorePage data = dataOpt.get();

            Optional<ResolvedParentApp> viaFullGame = extractFullGame(data);
            if (viaFullGame.isPresent()) {
                saveCached(new SteamAppParentEntry(appId, viaFullGame.get().appId(), viaFullGame.get().name()));
                return viaFullGame;
            }

            boolean looksLikePlaytest = "game".equals(data.type())
                && String.valueOf(data.name()).endsWith("Playtest");
            if (!looksLikePlaytest) {
                saveCached(new SteamAppParentEntry(appId, null, null));
                return Optional.empty();
            }

            Optional<String> parentId = redirectResolver.resolveParentAppId(appId);
            if (parentId.isEmpty()) return Optional.empty(); // uncertain — don't cache, allow retry

            Optional<SteamStorePage> parentPageOpt = fetchData(parentId.get(), Locale.ENGLISH);
            String parentName = parentPageOpt
                .flatMap(d -> Optional.ofNullable(d.name()).map(String::valueOf))
                .orElse(parentId.get());
            ResolvedParentApp resolved = new ResolvedParentApp(parentId.get(), parentName);
            if (parentPageOpt.isEmpty()) {
                // parent appdetails fetch failed (network/rate-limit) — don't cache a placeholder name, allow retry
                return Optional.of(resolved);
            }
            saveCached(new SteamAppParentEntry(appId, resolved.appId(), resolved.name()));
            return Optional.of(resolved);
        });
    }

    @SuppressWarnings("unchecked")
    private Optional<ResolvedParentApp> extractFullGame(SteamStorePage data) {
        SteamStorePage.FullGame fullgame = data.fullgame();
        if (fullgame == null || fullgame.appid() == null) return Optional.empty();
        String name = fullgame.name() != null ? fullgame.name() : String.valueOf(fullgame.appid());
        return Optional.of(new ResolvedParentApp(String.valueOf(fullgame.appid()), name));
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

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> fetchDescription(String appId, Locale locale) {
        return apiObservations.observe("steam_store", "fetch_description", () -> {
            String effectiveAppId = resolveEffectiveApp(appId)
                    .map(ResolvedParentApp::appId)
                    .orElse(appId);
            try {
                Optional<SteamStorePage> responseOpt = fetchData(effectiveAppId, locale);
                if (responseOpt.isEmpty()) return Optional.empty();
                SteamStorePage entry = responseOpt.get();
                String description = entry.shortDescription() != null ? entry.shortDescription() : "";
                return description.isBlank() ? Optional.empty() : Optional.of(description);
            } catch (Exception e) {
                log.warn("Steam fetchDescription failed for appId={}: {}", appId, e.getMessage());
                return Optional.empty();
            }
        });
    }

    @Override
    public Optional<SteamStorePage> fetchData(String appId, Locale locale, boolean resolveEffectiveParent) {
        return apiObservations.observe("steam_store", "fetch_store_data", () -> {
            String effectiveAppId = resolveEffectiveParent ? resolveEffectiveApp(appId)
                    .map(ResolvedParentApp::appId)
                    .orElse(appId) : appId;
            String lang = SteamLanguages.fromLocale(locale);
            record ResponseData(boolean success, SteamStorePage data) {}
            try {
                Map<String, ResponseData> response = restClient.get()
                        .uri(APP_DETAILS_URL + "?appids=" + effectiveAppId + "&l=" + lang)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                if (response == null || !response.get(effectiveAppId).success()) return Optional.empty();
                return Optional.ofNullable(response.get(effectiveAppId).data());
            } catch (Exception e) {
                log.warn("Steam fetchData failed for appId={}: {}", appId, e.getMessage(), e);
                return Optional.empty();
            }
        });
    }
}
