package fr.enimaloc.catapult.service.epic.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Resolves Epic catalog offers to their price tier, the key IGDB indexes Epic Store games by. */
@Component
@ConditionalOnBooleanProperty("epic.enabled")
public class EpicCatalogApi {

    private final RestClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String catalogUrl;

    public EpicCatalogApi(RestClient.Builder builder,
                          @Value("${epic.catalog-url:https://catalog-public-service-prod06.ol.epicgames.com"
                                  + "/catalog/api/shared/bulk/offers}") String catalogUrl) {
        this.client = builder.clone().build();
        this.catalogUrl = catalogUrl;
    }

    /** Maps each known offer id to its price tier; unknown offers are absent. */
    public Map<String, String> priceTiers(String token, Collection<String> offerIds) {
        if (offerIds.isEmpty()) return Map.of();
        URI uri = UriComponentsBuilder.fromUriString(catalogUrl)
                .queryParam("id", offerIds.toArray())
                .queryParam("returnItemDetails", false)
                .queryParam("country", "US")
                .queryParam("locale", "en")
                .build().encode().toUri();
        JsonNode root = client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 401) throw new EpicAuthException(EpicAuthException.Reason.REFRESH_REFUSED);
                    if (status < 200 || status >= 300) {
                        throw new RestClientException("Epic catalog service answered HTTP " + status);
                    }
                    byte[] raw = response.getBody().readAllBytes();
                    return raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw);
                });
        Map<String, String> tiers = new HashMap<>();
        for (String offerId : offerIds) {
            String tier = root.path(offerId).path("priceTier").asString("");
            if (!tier.isBlank()) tiers.put(offerId, tier);
        }
        return tiers;
    }
}
