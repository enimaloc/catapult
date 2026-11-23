package fr.enimaloc.catapult.service.enrichment.opencritic;

import fr.enimaloc.catapult.service.enrichment.EnrichmentUserAgent;
import fr.enimaloc.catapult.getter.opencritic.OpenCriticApiKeyRotator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link OpenCriticClient} over the RapidAPI distribution of OpenCritic. Each request takes the next key of the
 * shared pool; a 429 blocks that key and is retried once with the next one. Without a usable key nothing is sent
 * and an {@link IllegalStateException} is thrown, so it is never cached as "game absent".
 */
@Slf4j
@Component
public class HttpOpenCriticClient implements OpenCriticClient {

    private static final String HOST = "opencritic-api.p.rapidapi.com";
    private static final String BASE = "https://" + HOST;
    private static final String GAME_URL = "https://opencritic.com/game/";
    private static final Duration DEFAULT_BLOCK = Duration.ofSeconds(60);

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final Optional<OpenCriticApiKeyRotator> rotator;

    public HttpOpenCriticClient(RestClient.Builder builder, Optional<OpenCriticApiKeyRotator> rotator) {
        this.restClient = builder.defaultHeader(HttpHeaders.USER_AGENT, EnrichmentUserAgent.VALUE).build();
        this.rotator = rotator;
    }

    @Override
    public boolean isConfigured() {
        return rotator.map(OpenCriticApiKeyRotator::hasKeys).orElse(false);
    }

    @Override
    public List<Hit> search(String name) {
        List<Hit> hits = new ArrayList<>();
        Optional<JsonNode> answer = get(BASE + "/game/search?criteria={criteria}", name);
        if (answer.isPresent() && answer.get().isArray()) {
            for (JsonNode node : answer.get()) {
                if (node.hasNonNull("id") && node.hasNonNull("name")) {
                    hits.add(new Hit(node.get("id").asLong(), node.get("name").asString()));
                }
            }
        }
        return hits;
    }

    @Override
    public Optional<OpenCriticGame> game(long id) {
        return get(BASE + "/game/{id}", id)
                .filter(json -> json.isObject() && json.hasNonNull("id"))
                .map(json -> {
                    long gameId = json.get("id").asLong();
                    String name = json.path("name").asString(null);
                    int reviews = json.hasNonNull("numTopCriticReviews")
                            ? json.get("numTopCriticReviews").asInt(0) : json.path("numReviews").asInt(0);
                    return new OpenCriticGame(gameId, name, score(json.path("topCriticScore")),
                            score(json.path("percentRecommended")), blankToNull(json.path("tier").asString(null)),
                            Math.max(reviews, 0), GAME_URL + gameId + "/" + slug(name));
                });
    }

    private static Integer score(JsonNode node) {
        if (!node.isNumber() || node.asDouble() < 0) {
            return null;
        }
        return (int) Math.round(node.asDouble());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static String slug(String name) {
        if (name == null) {
            return "game";
        }
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "game" : slug;
    }

    /** GET with the pool's next key; throws when no key is usable; empty on 404 or unreadable body; retries once on 429. */
    private Optional<JsonNode> get(String uri, Object variable) {
        if (rotator.isEmpty()) {
            throw new IllegalStateException("OpenCritic is not enabled");
        }
        OpenCriticApiKeyRotator pool = rotator.get();
        for (int attempt = 0; ; attempt++) {
            Optional<String> key = pool.nextKey();
            if (key.isEmpty()) {
                throw new IllegalStateException("no usable OpenCritic API key");
            }
            pool.onCall(key.get());
            try {
                String body = restClient.get().uri(uri, variable)
                        .header("X-RapidAPI-Key", key.get())
                        .header("X-RapidAPI-Host", HOST)
                        .retrieve().body(String.class);
                return parse(body);
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.empty();
            } catch (HttpClientErrorException.TooManyRequests e) {
                pool.block(key.get(), retryAfter(e));
                if (attempt >= 1) {
                    throw e;
                }
            }
        }
    }

    private Optional<JsonNode> parse(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readTree(body));
        } catch (JacksonException e) {
            log.warn("OpenCritic answer is not JSON: {}", e.getOriginalMessage());
            return Optional.empty();
        }
    }

    private static Duration retryAfter(HttpClientErrorException e) {
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (header != null) {
            try {
                long seconds = Long.parseLong(header.trim());
                if (seconds > 0) {
                    return Duration.ofSeconds(seconds);
                }
            } catch (NumberFormatException ignored) {
                // HTTP-date form: fall back to the default
            }
        }
        return DEFAULT_BLOCK;
    }
}
