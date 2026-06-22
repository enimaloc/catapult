package fr.enimaloc.catapult.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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

    @Override
    public Map<String, Set<String>> fetchCcls(Collection<String> appIds) {
        if (appIds.isEmpty()) return Map.of();

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
    }

    @Override
    public Map<String, SteamTwSignals> fetchTwSignals(Collection<String> appIds) {
        if (appIds.isEmpty()) return Map.of();

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
    public Optional<String> resolveFullGameAppId(String appId) {
        String uri = APP_DETAILS_URL + "?appids=" + appId;
        try {
            Map<String, Object> response = restClient.get().uri(uri).retrieve().body(Map.class);
            if (response == null) return Optional.empty();

            Map<String, Object> entry = (Map<String, Object>) response.get(appId);
            if (entry == null || !Boolean.TRUE.equals(entry.get("success"))) return Optional.empty();

            Map<String, Object> data = (Map<String, Object>) entry.get("data");
            if (data == null) return Optional.empty();

            @SuppressWarnings("unchecked")
            Map<String, Object> fullgame = (Map<String, Object>) data.get("fullgame");
            if (fullgame == null) return Optional.empty();

            Object id = fullgame.get("appid");
            if (id == null) return Optional.empty();

            String resolved = String.valueOf(id);
            log.debug("Steam appId={} is a beta — resolved to fullgame.id={}", appId, resolved);
            return Optional.of(resolved);
        } catch (Exception e) {
            log.warn("Steam appdetails failed resolving parentid for appId={}: {}", appId, e.getMessage());
            return Optional.empty();
        }
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
