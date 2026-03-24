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

    public List<IgdbRatingDescriptor> getAllIgdbDescriptors() {
        return igdbDescriptorRepo.findAll();
    }

    @Transactional
    public void saveMappings(String cclId, Set<Long> descriptorIds) {
        twitchCclRepo.findById(cclId).ifPresent(ccl -> {
            Set<IgdbRatingDescriptor> mappings = descriptorIds.stream()
                .map(id -> igdbDescriptorRepo.findById(id).orElseThrow())
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
    void syncIgdbDescriptors(String appToken) {
        String clientIdToUse = igdbClientId.isBlank() ? twitchClientId : igdbClientId;
        if (clientIdToUse.isBlank()) return;

        List<Map<String, Object>> results = restClient.post()
            .uri(IGDB_API_URL + "/age_rating_content_descriptions")
            .header("Client-ID", clientIdToUse)
            .header("Authorization", "Bearer " + appToken)
            .body("fields id,description,organization; limit 500;")
            .retrieve()
            .body(List.class);

        if (results == null) return;

        for (Map<String, Object> item : results) {
            Long id = ((Number) item.get("id")).longValue();
            String description = (String) item.get("description");
            Integer orgId = item.get("organization") != null
                ? ((Number) item.get("organization")).intValue()
                : null;

            if (!igdbDescriptorRepo.existsById(id)) {
                IgdbRatingDescriptor d = new IgdbRatingDescriptor();
                d.setId(id);
                d.setDescription(description);
                d.setOrganizationId(orgId);
                d.setDisplayName(buildDisplayName(orgId, description));
                igdbDescriptorRepo.save(d);
            }
        }
        log.info("Synced {} IGDB rating descriptors", results.size());
    }

    private String buildDisplayName(Integer orgId, String description) {
        String org = orgId != null ? ORG_NAMES.getOrDefault(orgId, "Org" + orgId) : "Unknown";
        return org + " — " + description;
    }
}
