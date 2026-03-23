package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.TwitchCcl;
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

    private final RestClient restClient;

    /**
     * Fetches CCLs from Steam Store age ratings for a batch of Steam app IDs.
     * Returns a map of steamAppId → Set&lt;TwitchCcl&gt;.
     */
    public Map<String, Set<TwitchCcl>> fetchCcls(Collection<String> appIds) {
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

            Map<String, Set<TwitchCcl>> result = new HashMap<>();
            for (String appId : appIds) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) response.get(appId);
                if (entry == null || !Boolean.TRUE.equals(entry.get("success"))) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) entry.get("data");
                if (data == null) continue;

                Set<TwitchCcl> ccls = extractCcls(data);
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
    private Set<TwitchCcl> extractCcls(Map<String, Object> data) {
        Set<TwitchCcl> ccls = new HashSet<>();

        Map<String, Object> ratings = (Map<String, Object>) data.get("ratings");
        if (ratings == null) return ccls;

        for (Map.Entry<String, Object> entry : ratings.entrySet()) {
            Map<String, Object> rating = (Map<String, Object>) entry.getValue();
            if (rating == null) continue;

            String org   = entry.getKey();
            String level = String.valueOf(rating.getOrDefault("rating", "")).toLowerCase(Locale.ROOT);

            // MatureGame: ESRB m/ao or PEGI 18
            if (("esrb".equals(org) && (level.equals("m") || level.equals("ao")))
                || ("pegi".equals(org) && level.equals("18"))) {
                ccls.add(TwitchCcl.MatureGame);
            }

            // Descriptor text matching
            String descriptors = String.valueOf(rating.getOrDefault("descriptors", ""))
                .toLowerCase(Locale.ROOT);
            if (descriptors.isBlank()) continue;

            if (TwitchCcl.KW_VIOLENCE.stream().anyMatch(descriptors::contains)) ccls.add(TwitchCcl.ViolentGraphic);
            if (TwitchCcl.KW_SEXUAL.stream().anyMatch(descriptors::contains))   ccls.add(TwitchCcl.SexualThemes);
            if (TwitchCcl.KW_DRUGS.stream().anyMatch(descriptors::contains))    ccls.add(TwitchCcl.DrugsIntoxication);
            if (TwitchCcl.KW_GAMBLING.stream().anyMatch(descriptors::contains)) ccls.add(TwitchCcl.Gambling);
            if (TwitchCcl.KW_LANGUAGE.stream().anyMatch(descriptors::contains)) ccls.add(TwitchCcl.ProfanityVulgarity);
        }
        return ccls;
    }
}
