package fr.enimaloc.catapult.getter.dtdd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Component
@ConditionalOnBooleanProperty("dtdd.enabled")
public class RealDtddApiClient implements DtddApiClient {

    // DTDD adds fields to its answers (severity, 2026-11): one we don't know must not fail the whole call
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RestClient client;
    private final DtddApiKeyRotator rotator;
    private final ExternalApiObservations apiObservations;
    /** The key each call takes: any usable one, or (a {@link #reserving} view) only those above a reserve. */
    private final Supplier<Optional<String>> keys;

    @Autowired
    public RealDtddApiClient(@Value("${dtdd.api-base-url}") String baseUrl,
                             RestClient.Builder builder,
                             DtddApiKeyRotator rotator,
                             ExternalApiObservations apiObservations) {
        // Take the Boot-managed builder so the trace-logging customizer +
        // buffering request factory wired in WebClientConfig apply here too.
        this(builder.baseUrl(baseUrl).build(), rotator, apiObservations);
    }

    // visible for tests
    RealDtddApiClient(RestClient client, DtddApiKeyRotator rotator, ExternalApiObservations apiObservations) {
        this(client, rotator, apiObservations, rotator::nextKey);
    }

    private RealDtddApiClient(RestClient client, DtddApiKeyRotator rotator, ExternalApiObservations apiObservations,
                              Supplier<Optional<String>> keys) {
        this.client = client;
        this.rotator = rotator;
        this.apiObservations = apiObservations;
        this.keys = keys;
    }

    @Override
    public DtddApiClient reserving(int monthlyReserve) {
        return new RealDtddApiClient(client, rotator, apiObservations, () -> rotator.nextKeyAbove(monthlyReserve));
    }

    @Override
    public Optional<List<DtddSearchResult>> search(String query, @Nullable String mediaType) {
        return apiObservations.observe("dtdd", "search", () ->
            callWithRetry(key -> client.get()
                    .uri("/v3/items?q={q}", query)
                    .header("X-API-KEY", key)
                    .retrieve()
                    .toEntity(String.class), this::parseSearch)
                    .map(list -> list.stream()
                            .filter(result -> mediaType == null || mediaType.equals(result.mediaType()))
                            .toList())
        );
    }

    @Override
    public Optional<DtddTopics> fetchTopics(long dtddId) {
        return apiObservations.observe("dtdd", "fetch_topics", () ->
            item(dtddId).map(item -> {
                List<String> yes = new ArrayList<>(), no = new ArrayList<>(), mostly = new ArrayList<>();
                Map<String, DtddSeverity> severities = new LinkedHashMap<>();
                for (DtddTopicItemStat topicItemStat : item.topicItemStats()) {
                    if (topicItemStat.yesSum() > topicItemStat.noSum()) yes.add(topicItemStat.topicName());
                    else if (topicItemStat.yesSum() < topicItemStat.noSum()) no.add(topicItemStat.topicName());
                    else mostly.add(topicItemStat.topicName());
                    if (topicItemStat.severity() != null && topicItemStat.topicName() != null) {
                        severities.put(topicItemStat.topicName(), topicItemStat.severity());
                    }
                }
                return new DtddTopics(yes, no, mostly, severities);
            })
        );
    }

    @Override
    public Optional<DtddItem> item(long dtddId) {
        return apiObservations.observe("dtdd", "item", () ->
            callWithRetry(key -> client.get()
                    .uri("/v3/items/{id}", dtddId)
                    .header("X-API-KEY", key)
                    .retrieve()
                    .toEntity(String.class), this::parseItem)
        );
    }

    private <T> Optional<T> callWithRetry(Function<String, ResponseEntity<String>> call, Function<String, T> parser) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<String> keyOpt = keys.get();
            if (keyOpt.isEmpty()) return Optional.empty();
            String key = keyOpt.get();
            try {
                rotator.onCall(key);
                ResponseEntity<String> response = call.apply(key);
                rotator.onQuota(key, header(response.getHeaders(), "X-RateLimit-Remaining-Minute"),
                        header(response.getHeaders(), "X-RateLimit-Remaining-Month"),
                        header(response.getHeaders(), "X-RateLimit-Limit-Minute"),
                        header(response.getHeaders(), "X-RateLimit-Limit-Month"));
                return Optional.ofNullable(parser.apply(response.getBody()));
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

    /** A quota header's number, null when DTDD didn't send it (or sent something else). */
    private static Integer header(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseRetryAfter(String header) {
        if (header == null || header.isBlank()) return 60;
        try {
            return Math.max(1, Integer.parseInt(header.trim()));
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    private DtddItem parseItem(String body) {
        try {
            return MAPPER.readValue(body, DtddItem.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private List<DtddSearchResult> parseSearch(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            List<DtddSearchResult> out = new ArrayList<>();
            for (JsonNode it : root) {
                out.add(new DtddSearchResult(
                        it.path("id").asLong(),
                        it.path("name").asText(null),
                        "https://www.doesthedogdie.com/media/" + it.path("id").asLong(),
                        it.path("itemTypeName").asText(null),
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
                if (y > n) yes.add(name);
                else if (n > y) no.add(name);
                else mostly.add(name);
            }
            return new DtddTopics(yes, no, mostly);
        } catch (Exception e) {
            log.warn("DTDD topics parse failed: {}", e.getMessage());
            return new DtddTopics(List.of(), List.of(), List.of());
        }
    }
}
