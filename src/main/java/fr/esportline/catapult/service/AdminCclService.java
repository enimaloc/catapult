package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.IgdbRatingDescriptor;
import fr.esportline.catapult.domain.TwitchCclDefinition;
import fr.esportline.catapult.repository.IgdbRatingDescriptorRepository;
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
import java.util.stream.Stream;

/**
 * Gestion de la configuration admin des CCLs.
 * Au démarrage, synchronise les CCLs Twitch et les descripteurs de contenu IGDB depuis leurs APIs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCclService {

    private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String TWITCH_CCL_URL = "https://api.twitch.tv/helix/content_classification_labels";
    private static final String IGDB_API_URL = "https://api.igdb.com/v4";

    private static final Map<Integer, String> ORG_NAMES = Map.of(
        1, "ESRB", 2, "PEGI", 3, "CERO", 4, "USK", 5, "GRAC", 6, "CLASS_IND", 7, "ACB"
    );

    private final RestClient restClient;
    private final TwitchCclDefinitionRepository twitchCclRepo;
    private final IgdbRatingDescriptorRepository igdbDescriptorRepo;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    @Value("${twitch.client-secret:}")
    private String twitchClientSecret;

    @Value("${app.igdb.client-id:}")
    private String igdbClientId;

    @PostConstruct
    public void init() {
        try {
            String appToken = fetchAppToken();
            if (appToken != null) {
                syncTwitchCcls(appToken);
                syncIgdbDescriptors(appToken);
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

    /**
     * Returns one representative descriptor per unique description text,
     * sorted alphabetically — used to de-duplicate the admin select list.
     */
    public List<IgdbRatingDescriptor> getAllIgdbDescriptors() {
        return igdbDescriptorRepo.findAll().stream()
            .collect(Collectors.toMap(
                IgdbRatingDescriptor::getDescription,
                d -> d,
                (a, b) -> a.getId() < b.getId() ? a : b  // keep lowest ID as representative
            ))
            .values()
            .stream()
            .sorted(Comparator.comparing(IgdbRatingDescriptor::getDescription))
            .collect(Collectors.toList());
    }

    /**
     * Maps the CCL to all descriptors whose description text matches any of the
     * selected representative IDs — so selecting "Violence" once covers both
     * "ESRB — Violence" (id=29) and "PEGI — Violence" (id=50).
     */
    @Transactional
    public void saveMappings(String cclId, Set<Long> representativeIds) {
        twitchCclRepo.findById(cclId).ifPresent(ccl -> {
            Set<IgdbRatingDescriptor> expanded = representativeIds.stream()
                .flatMap(id -> igdbDescriptorRepo.findById(id)
                    .map(d -> igdbDescriptorRepo.findByDescription(d.getDescription()).stream())
                    .orElse(Stream.empty()))
                .collect(Collectors.toSet());
            ccl.setIgdbMappings(expanded);
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

    /**
     * Fetches age_ratings entries from IGDB and extracts all unique
     * rating_content_descriptions (with their organization context) for display.
     */
    @SuppressWarnings("unchecked")
    @Transactional
    void syncIgdbDescriptors(String appToken) {
        String clientIdToUse = igdbClientId.isBlank() ? twitchClientId : igdbClientId;
        if (clientIdToUse.isBlank()) return;

        List<Map<String, Object>> results = restClient.post()
            .uri(IGDB_API_URL + "/age_ratings")
            .header("Client-ID", clientIdToUse)
            .header("Authorization", "Bearer " + appToken)
            .body("fields rating_content_descriptions.id," +
                  "rating_content_descriptions.description," +
                  "rating_content_descriptions.organization;" +
                  " where rating_content_descriptions != null; limit 500;")
            .retrieve()
            .body(List.class);

        if (results == null) return;

        int newCount = 0;
        for (Map<String, Object> ageRating : results) {
            List<Map<String, Object>> descs =
                (List<Map<String, Object>>) ageRating.get("rating_content_descriptions");
            if (descs == null) continue;

            for (Map<String, Object> item : descs) {
                Long id = ((Number) item.get("id")).longValue();
                if (igdbDescriptorRepo.existsById(id)) continue;

                String description = (String) item.get("description");
                Integer orgId = item.get("organization") != null
                    ? ((Number) item.get("organization")).intValue()
                    : null;

                IgdbRatingDescriptor d = new IgdbRatingDescriptor();
                d.setId(id);
                d.setDescription(description);
                d.setOrganizationId(orgId);
                d.setDisplayName(buildDisplayName(orgId, description));
                igdbDescriptorRepo.save(d);
                newCount++;
            }
        }
        log.info("Synced {} new IGDB rating descriptors", newCount);
    }

    private String buildDisplayName(Integer orgId, String description) {
        String org = orgId != null ? ORG_NAMES.getOrDefault(orgId, "Org" + orgId) : "Unknown";
        return org + " — " + description;
    }

}
