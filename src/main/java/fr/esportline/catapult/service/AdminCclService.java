package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.IgdbAgeRatingCategory;
import fr.esportline.catapult.domain.TwitchCclDefinition;
import fr.esportline.catapult.repository.IgdbAgeRatingCategoryRepository;
import fr.esportline.catapult.repository.TwitchCclDefinitionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Gestion de la configuration admin des CCLs.
 * Au démarrage, synchronise les CCLs Twitch et les catégories d'âge IGDB depuis leurs APIs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCclService {

    private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String TWITCH_CCL_URL = "https://api.twitch.tv/helix/content_classification_labels";
    private static final String IGDB_API_URL = "https://api.igdb.com/v4";

    private static final Map<Integer, String> CATEGORY_NAMES = Map.of(
        1, "ESRB", 2, "PEGI", 3, "CERO", 4, "USK", 5, "GRAC", 6, "CLASS_IND", 7, "ACB"
    );
    private static final Map<Integer, String> ESRB_LABELS = Map.of(
        12, "E", 3, "E10+", 10, "T", 11, "M", 8, "AO"
    );
    private static final Map<Integer, String> PEGI_LABELS = Map.of(
        1, "3", 2, "7", 3, "12", 4, "16", 5, "18"
    );

    private final RestClient restClient;
    private final TwitchCclDefinitionRepository twitchCclRepo;
    private final IgdbAgeRatingCategoryRepository igdbRatingRepo;

    @Value("${spring.security.oauth2.client.registration.twitch.client-id:}")
    private String twitchClientId;

    @Value("${spring.security.oauth2.client.registration.twitch.client-secret:}")
    private String twitchClientSecret;

    @Value("${app.igdb.client-id:}")
    private String igdbClientId;

    @PostConstruct
    public void init() {
        try {
            String appToken = fetchAppToken();
            if (appToken != null) {
                syncTwitchCcls(appToken);
                syncIgdbCategories(appToken);
            }
        } catch (Exception e) {
            log.warn("AdminCclService startup sync failed — admin CCL data may be stale: {}", e.getMessage());
        }
    }

    public void refreshFromApi() {
        init();
    }

    public List<TwitchCclDefinition> getAllCcls() {
        return twitchCclRepo.findAll();
    }

    public List<IgdbAgeRatingCategory> getAllIgdbCategories() {
        return igdbRatingRepo.findAll();
    }

    @Transactional
    public void saveMappings(String cclId, Set<Long> igdbCategoryIds) {
        twitchCclRepo.findById(cclId).ifPresent(ccl -> {
            Set<IgdbAgeRatingCategory> mappings = igdbCategoryIds.stream()
                .map(id -> igdbRatingRepo.findById(id).orElseThrow())
                .collect(Collectors.toSet());
            ccl.setIgdbMappings(mappings);
            twitchCclRepo.save(ccl);
        });
    }

    @SuppressWarnings("unchecked")
    private String fetchAppToken() {
        if (twitchClientId.isBlank() || twitchClientSecret.isBlank()) {
            log.warn("Twitch client credentials not configured — skipping CCL sync");
            return null;
        }
        Map<String, Object> response = restClient.post()
            .uri(TWITCH_TOKEN_URL
                + "?client_id=" + twitchClientId
                + "&client_secret=" + twitchClientSecret
                + "&grant_type=client_credentials")
            .retrieve()
            .body(Map.class);
        return response != null ? (String) response.get("access_token") : null;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    void syncTwitchCcls(String appToken) {
        Map<String, Object> response = restClient.get()
            .uri(TWITCH_CCL_URL)
            .header("Authorization", "Bearer " + appToken)
            .header("Client-Id", twitchClientId)
            .retrieve()
            .body(Map.class);

        if (response == null) return;
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null) return;

        for (Map<String, Object> item : data) {
            String id = (String) item.get("id");
            TwitchCclDefinition def = twitchCclRepo.findById(id)
                .orElseGet(() -> { var d = new TwitchCclDefinition(); d.setId(id); return d; });
            def.setName((String) item.get("name"));
            def.setDescription((String) item.get("description"));
            twitchCclRepo.save(def);
        }
        log.info("Synced {} Twitch CCL definitions", data.size());
    }

    @SuppressWarnings("unchecked")
    @Transactional
    void syncIgdbCategories(String appToken) {
        String clientIdToUse = igdbClientId.isBlank() ? twitchClientId : igdbClientId;
        if (clientIdToUse.isBlank()) return;

        List<Map<String, Object>> results = restClient.post()
            .uri(IGDB_API_URL + "/age_ratings")
            .header("Client-ID", clientIdToUse)
            .header("Authorization", "Bearer " + appToken)
            .body("fields category,rating; limit 500; sort category asc;")
            .retrieve()
            .body(List.class);

        if (results == null) return;

        Set<String> seen = new HashSet<>();
        for (Map<String, Object> item : results) {
            Integer categoryId = ((Number) item.get("category")).intValue();
            Integer rating = ((Number) item.get("rating")).intValue();
            String key = categoryId + ":" + rating;
            if (!seen.add(key)) continue;

            igdbRatingRepo.findByCategoryIdAndRating(categoryId, rating)
                .orElseGet(() -> {
                    var cat = new IgdbAgeRatingCategory();
                    cat.setCategoryId(categoryId);
                    cat.setRating(rating);
                    cat.setDisplayName(buildDisplayName(categoryId, rating));
                    return igdbRatingRepo.save(cat);
                });
        }
        log.info("Synced {} unique IGDB age rating categories", seen.size());
    }

    private String buildDisplayName(int categoryId, int ratingInt) {
        String catName = CATEGORY_NAMES.getOrDefault(categoryId, "CAT" + categoryId);
        String ratingLabel = switch (categoryId) {
            case 1 -> ESRB_LABELS.getOrDefault(ratingInt, String.valueOf(ratingInt));
            case 2 -> "PEGI " + PEGI_LABELS.getOrDefault(ratingInt, String.valueOf(ratingInt));
            default -> String.valueOf(ratingInt);
        };
        return catName + " " + ratingLabel;
    }
}
