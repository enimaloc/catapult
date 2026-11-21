package fr.enimaloc.catapult.service.enrichment.protondb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/** {@link ProtonDbClient} over ProtonDB's unofficial, undocumented summaries endpoint (no authentication). */
@Slf4j
@Component
public class HttpProtonDbClient implements ProtonDbClient {

    private static final String SUMMARY_URL = "https://www.protondb.com/api/v1/reports/summaries/{appId}.json";
    private static final String APP_URL = "https://www.protondb.com/app/";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;

    public HttpProtonDbClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public Optional<ProtonDbSummary> fetchSummary(String steamAppId) {
        String body;
        try {
            body = restClient.get().uri(SUMMARY_URL, steamAppId).retrieve().body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode json = mapper.readTree(body);
            if (!json.isObject() || !json.hasNonNull("tier")) {
                return Optional.empty();
            }
            return Optional.of(new ProtonDbSummary(
                    json.path("tier").asString(),
                    json.hasNonNull("trendingTier") ? json.get("trendingTier").asString() : null,
                    json.hasNonNull("bestReportedTier") ? json.get("bestReportedTier").asString() : null,
                    json.hasNonNull("confidence") ? json.get("confidence").asString() : null,
                    json.path("score").asDouble(0),
                    json.path("total").asInt(0),
                    APP_URL + steamAppId));
        } catch (JacksonException e) {
            log.warn("ProtonDB summary for {} is not JSON: {}", steamAppId, e.getOriginalMessage());
            return Optional.empty();
        }
    }
}
