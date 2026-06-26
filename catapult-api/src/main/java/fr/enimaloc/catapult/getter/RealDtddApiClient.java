package fr.enimaloc.catapult.getter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@Slf4j
@Component
@Profile("!mock")
@ConditionalOnBooleanProperty("dtdd.enabled")
public class RealDtddApiClient implements DtddApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final DtddApiKeyRotator rotator;

    @Autowired
    public RealDtddApiClient(@Value("${dtdd.api-base-url}") String baseUrl,
                             RestClient.Builder builder,
                             DtddApiKeyRotator rotator) {
        // Take the Boot-managed builder so the trace-logging customizer +
        // buffering request factory wired in WebClientConfig apply here too.
        this(builder.baseUrl(baseUrl).build(), rotator);
    }

    // visible for tests
    RealDtddApiClient(RestClient client, DtddApiKeyRotator rotator) {
        this.client = client;
        this.rotator = rotator;
    }

    @Override
    public Optional<List<DtddSearchResult>> search(String query) {
        return callWithRetry(key -> client.get()
            .uri(uri -> uri.path("/v1/search").queryParam("q", query).build())
            .header("X-API-KEY", key)
            .retrieve()
            .body(String.class), this::parseSearch);
    }

    @Override
    public Optional<DtddTopics> fetchTopics(long dtddId) {
        return callWithRetry(key -> client.get()
            .uri("/v1/media/" + dtddId)
            .header("X-API-KEY", key)
            .retrieve()
            .body(String.class), this::parseTopics);
    }

    private <T> Optional<T> callWithRetry(Function<String, String> call, Function<String, T> parser) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<String> keyOpt = rotator.nextKey();
            if (keyOpt.isEmpty()) return Optional.empty();
            String key = keyOpt.get();
            try {
                String body = call.apply(key);
                return Optional.ofNullable(parser.apply(body));
            } catch (RestClientResponseException e) {
                HttpStatusCode status = e.getStatusCode();
                if (status.value() == 404) return Optional.empty();
                if (status.value() == 429) {
                    String retryAfter = e.getResponseHeaders() != null
                        ? e.getResponseHeaders().getFirst("Retry-After") : null;
                    int seconds = parseRetryAfter(retryAfter);
                    rotator.onKeyRateLimited(key, seconds);
                    continue; // retry once
                }
                log.warn("DTDD HTTP {}: {}", status.value(), e.getResponseBodyAsString());
                return Optional.empty();
            } catch (Exception e) {
                log.warn("DTDD call failed: {}", e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private int parseRetryAfter(String header) {
        if (header == null || header.isBlank()) return 60;
        try { return Math.max(1, Integer.parseInt(header.trim())); }
        catch (NumberFormatException e) { return 60; }
    }

    private List<DtddSearchResult> parseSearch(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode items = root.path("items");
            List<DtddSearchResult> out = new ArrayList<>();
            for (JsonNode it : items) {
                out.add(new DtddSearchResult(
                    it.path("id").asLong(),
                    it.path("name").asText(null),
                    it.path("slug").asText(null),
                    it.path("type").asText(null),
                    it.path("posterUrl").isNull() ? null : it.path("posterUrl").asText(null)
                ));
            }
            return out;
        } catch (Exception e) {
            log.warn("DTDD search parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private DtddTopics parseTopics(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode stats = root.path("topicItemStats");
            List<String> yes = new ArrayList<>(), no = new ArrayList<>(), mostly = new ArrayList<>();
            for (JsonNode s : stats) {
                String name = s.path("topic").path("name").asText(null);
                if (name == null) continue;
                int y = s.path("yesSum").asInt(0);
                int n = s.path("noSum").asInt(0);
                if (y > n)      yes.add(name);
                else if (n > y) no.add(name);
                else            mostly.add(name);
            }
            return new DtddTopics(yes, no, mostly);
        } catch (Exception e) {
            log.warn("DTDD topics parse failed: {}", e.getMessage());
            return new DtddTopics(List.of(), List.of(), List.of());
        }
    }
}
