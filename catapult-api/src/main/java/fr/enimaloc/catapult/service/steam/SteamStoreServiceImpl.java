package fr.enimaloc.catapult.service.steam;

import fr.enimaloc.catapult.domain.steam.SteamAppParentEntry;
import fr.enimaloc.catapult.getter.steam.SteamPlaytestRedirectResolver;
import fr.enimaloc.catapult.repository.steam.SteamAppParentRepository;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
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

    /** Without a Retry-After, the first wait after a 429; it doubles while they follow each other, up to the max. */
    private static final Duration RATE_LIMIT_FIRST = Duration.ofSeconds(60);
    private static final Duration RATE_LIMIT_MAX = Duration.ofMinutes(10);

    @Setter(AccessLevel.PACKAGE)
    private Clock clock = Clock.systemUTC();
    /** Until when the store asked to be left alone; null: it may be called. */
    private volatile Instant rateLimitedUntil;
    /** The wait the next 429 without a Retry-After gets: doubled by each, reset by an answer. */
    private volatile Duration nextWait = RATE_LIMIT_FIRST;

    @Override
    public Optional<Instant> rateLimitedUntil() {
        Instant until = rateLimitedUntil;
        return until != null && until.isAfter(clock.instant()) ? Optional.of(until) : Optional.empty();
    }

    /** The store answered 429: left alone for its Retry-After (seconds or an HTTP date), else the next wait. */
    private void onRateLimited(HttpHeaders headers) {
        Instant now = clock.instant();
        Duration wait = retryAfter(headers == null ? null : headers.getFirst("Retry-After"), now)
                .orElse(nextWait);
        if (wait.compareTo(RATE_LIMIT_MAX) > 0) wait = RATE_LIMIT_MAX;
        Instant until = now.plus(wait);
        if (rateLimitedUntil == null || until.isAfter(rateLimitedUntil)) rateLimitedUntil = until;
        Duration doubled = nextWait.multipliedBy(2);
        nextWait = doubled.compareTo(RATE_LIMIT_MAX) > 0 ? RATE_LIMIT_MAX : doubled;
        log.warn("Steam store rate limited (429): left alone until {}", rateLimitedUntil);
    }

    static Optional<Duration> retryAfter(String header, Instant now) {
        if (header == null || header.isBlank()) return Optional.empty();
        String value = header.trim();
        try {
            return Optional.of(Duration.ofSeconds(Math.max(1, Long.parseLong(value))));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(value,
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Optional.of(at.isAfter(now) ? Duration.between(now, at) : Duration.ofSeconds(1));
            } catch (DateTimeParseException notADate) {
                return Optional.empty();
            }
        }
    }

    /** One app's entry in an {@code appdetails} response, keyed by appId. */
    record AppDetailsEntry(boolean success, SteamStorePage data) {}

    @Override
    public Map<String, Set<String>> fetchCcls(Collection<String> appIds) {
        if (appIds.isEmpty()) return Map.of();
        return apiObservations.observe("steam_store", "fetch_ccls", () -> {
            try {
                Map<String, Set<String>> result = new HashMap<>();
                for (String appId : appIds) {
                    // An app Steam doesn't resolve is skipped, not fatal for the others;
                    // apps without any matching descriptor get no entry at all.
                    fetchData(appId, Locale.ENGLISH)
                            .map(SteamStoreServiceImpl::extractCcls)
                            .filter(ccls -> !ccls.isEmpty())
                            .ifPresent(ccls -> result.put(appId, ccls));
                }
                log.debug("Steam store fetch for {} appIds: {} had rating data", appIds.size(), result.size());
                return result;

            } catch (Exception e) {
                log.warn("Steam store appdetails failed for appIds={}: {}", appIds, e.getMessage());
                return Map.of();
            }
        });
    }

    private static Set<String> extractCcls(SteamStorePage data) {
        Set<String> ccls = new HashSet<>();
        SteamStorePage.Ratings ratings = data.ratings();
        if (ratings == null) return ccls;

        for (SteamStorePage.Ratings.Rating entry : ratings.entries()) {
            // Steam writes descriptors in title case ("Blood and Gore"); keywords are lowercase.
            String descriptors = Objects.requireNonNullElse(entry.descriptors(), "").toLowerCase(Locale.ROOT);
            if (descriptors.isBlank()) continue;
            KEYWORDS.forEach((id, keywords) -> {
                if (keywords.stream().anyMatch(descriptors::contains)) ccls.add(id);
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
                    fetchData(appId, Locale.ENGLISH)
                            .ifPresent(page -> result.put(appId, extractTwSignals(page)));
                }
                return result;

            } catch (Exception e) {
                log.warn("Steam fetchTwSignals failed for appIds={}: {}", appIds, e.getMessage());
                return Map.of();
            }
        });
    }

    /** Package-private for unit testing. */
    static SteamTwSignals extractTwSignals(SteamStorePage data) {
        SteamStorePage.ContentDescriptors cd = data.contentDescriptors();
        if (cd == null) return SteamTwSignals.empty();
        Set<Integer> ids = new HashSet<>();
        if (cd.ids() != null) {
            for (int id : cd.ids()) ids.add(id);
        }
        String notes = (cd.note() != null ? cd.note() : "").toLowerCase(Locale.ROOT);
        return new SteamTwSignals(ids, notes);
    }

    @Override
    public Optional<ResolvedParentApp> resolveEffectiveApp(String appId) {
        return apiObservations.observe("steam_store", "resolve_effective_app", () -> {
            Optional<SteamAppParentEntry> fresh = findCached(appId)
                .filter(e -> e.getResolvedAt().isAfter(Instant.now().minus(PARENT_CACHE_TTL)));
            if (fresh.isPresent()) {
                SteamAppParentEntry entry = fresh.get();
                return entry.getParentAppId() == null
                    ? Optional.empty()
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
            SteamLanguage lang = SteamLanguage.fromLocale(locale);
            if (rateLimitedUntil().isPresent()) {
                log.debug("Steam store left alone (rate limited): no appdetails for appId={}", appId);
                return Optional.empty();
            }
            try {
                Map<String, AppDetailsEntry> response = restClient.get()
                        .uri(APP_DETAILS_URL + "?appids=" + effectiveAppId + "&l=" + lang)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                nextWait = RATE_LIMIT_FIRST;
                AppDetailsEntry entry = response == null ? null : response.get(effectiveAppId);
                if (entry == null || !entry.success()) return Optional.empty();
                return Optional.ofNullable(entry.data());
            } catch (HttpClientErrorException.TooManyRequests e) {
                onRateLimited(e.getResponseHeaders());
                return Optional.empty();
            } catch (Exception e) {
                log.warn("Steam fetchData failed for appId={}: {}", appId, e.getMessage(), e);
                return Optional.empty();
            }
        });
    }

    @Override
    public Optional<String> fetchIADisclosure(String appId, Locale locale) {
        return apiObservations.observe("steam_store", "fetch_ia_disclosure", () -> {
            String effectiveAppId = resolveEffectiveApp(appId)
                    .map(ResolvedParentApp::appId)
                    .orElse(appId);
            SteamLanguage lang = SteamLanguage.fromLocale(locale);
            if (rateLimitedUntil().isPresent()) return Optional.empty();
            try {
                String response = restClient.get()
                        .uri("https://store.steampowered.com/app/" + effectiveAppId + "?l=" + lang)
                        .retrieve()
                        .body(String.class);
                if (response == null || response.isBlank()) return Optional.empty();
                return Optional.of(response);
            } catch (HttpClientErrorException.TooManyRequests e) {
                onRateLimited(e.getResponseHeaders());
                return Optional.empty();
            } catch (Exception e) {
                log.warn("Steam fetchIADisclosure failed for appId={}: {}", appId, e.getMessage(), e);
                return Optional.empty();
            }
        });
    }
}
