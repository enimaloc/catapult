package fr.esportline.catapult.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Client pour l'API publique Steam Store (appdetails).
 * Extrait les CCLs à partir des descripteurs de classification d'âge.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SteamStoreService {

    private static final String APP_DETAILS_URL = "https://store.steampowered.com/api/appdetails";

    private static final Map<String, Set<String>> KEYWORDS = Map.of(
        "ViolentGraphic",    Set.of("blood", "gore", "violence", "violent", "killing", "combat", "death", "injury"),
        "SexualThemes",      Set.of("nudity", "sexual", "sex", "suggestive", "erotic", "partial nudity"),
        "DrugsIntoxication", Set.of("drug", "alcohol", "tobacco", "substance", "intoxication"),
        "Gambling",          Set.of("gambling", "simulated gambling", "betting"),
        "ProfanityVulgarity",Set.of("language", "profanity", "crude", "bad language", "strong language", "lyrics")
    );

    private final RestClient restClient;

    /**
     * Fetches CCLs from Steam Store age ratings for a batch of Steam app IDs.
     * Returns a map of steamAppId → Set of Twitch CCL IDs.
     */
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
                if (entry == null || !Boolean.TRUE.equals(entry.get("success"))) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) entry.get("data");
                if (data == null) continue;

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

    @SuppressWarnings("unchecked")
    private Set<String> extractCcls(Map<String, Object> data) {
        Set<String> ccls = new HashSet<>();

        Map<String, Object> ratings = (Map<String, Object>) data.get("ratings");
        if (ratings == null) return ccls;

        for (Map.Entry<String, Object> entry : ratings.entrySet()) {
            Map<String, Object> rating = (Map<String, Object>) entry.getValue();
            if (rating == null) continue;

            String org   = entry.getKey();
            String level = String.valueOf(rating.getOrDefault("rating", "")).toLowerCase(Locale.ROOT);

            // MatureGame is set automatically by Twitch — no need to suggest it
            // ESRB M/AO or PEGI 18 → already handled by Twitch based on game rating

            String descriptors = String.valueOf(rating.getOrDefault("descriptors", ""))
                .toLowerCase(Locale.ROOT);
            if (descriptors.isBlank()) continue;

            KEYWORDS.forEach((cclId, keywords) -> {
                if (keywords.stream().anyMatch(descriptors::contains)) ccls.add(cclId);
            });
        }
        return ccls;
    }
}
