package fr.enimaloc.catapult.service.enrichment.steamdeck;

import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility.Category;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility.TestedItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link SteamDeckClient} backed by the Steam store's (undocumented) deck compatibility report endpoint. */
@Slf4j
@Component
public class HttpSteamDeckClient implements SteamDeckClient {

    private static final String URL =
            "https://store.steampowered.com/saleaction/ajaxgetdeckappcompatibilityreport?nAppID={appId}";

    private final RestClient restClient;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public HttpSteamDeckClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public Optional<SteamDeckCompatibility> fetch(String steamAppId) {
        String body = restClient.get().uri(URL, steamAppId).retrieve().body(String.class);
        return parse(steamAppId, body);
    }

    private Optional<SteamDeckCompatibility> parse(String steamAppId, String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode results = mapper.readTree(body).path("results");
            if (!results.isObject()) {
                return Optional.empty();
            }
            Category category = Category.fromCode(results.path("resolved_category").asInt(0));
            List<TestedItem> items = new ArrayList<>();
            for (JsonNode item : results.path("resolved_items")) {
                items.add(new TestedItem(item.path("display_type").asString(""),
                        item.path("loc_token").asString("")));
            }
            return Optional.of(new SteamDeckCompatibility(category, List.copyOf(items)));
        } catch (RuntimeException e) {
            log.warn("Unusable Steam Deck report for app {}: {}", steamAppId, e.toString());
            return Optional.empty();
        }
    }
}
