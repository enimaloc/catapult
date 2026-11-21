package fr.enimaloc.catapult.service.enrichment.prices;

import fr.enimaloc.catapult.getter.itad.ItadApiKeyRotator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link ItadClient} over the IsThereAnyDeal API. Each request takes the next key of the shared pool, sent as the
 * {@code key} query parameter; a 429 blocks that key for its {@code Retry-After} and the exception is thrown (the
 * cache then serves the stale value, if any). Without a key nothing is sent and an exception is thrown. A shop id
 * is kept as the string of ITAD's id.
 */
@Slf4j
@Component
public class HttpItadClient implements ItadClient {

    private static final String BASE = "https://api.isthereanydeal.com";
    private static final String GAME_URL = "https://isthereanydeal.com/game/";
    private static final Duration DEFAULT_BLOCK = Duration.ofSeconds(60);

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final Optional<ItadApiKeyRotator> rotator;

    public HttpItadClient(RestClient.Builder builder, Optional<ItadApiKeyRotator> rotator) {
        this.restClient = builder.build();
        this.rotator = rotator;
    }

    @Override
    public boolean isConfigured() {
        return rotator.map(ItadApiKeyRotator::hasKeys).orElse(false);
    }

    @Override
    public Optional<ItadGame> lookupBySteamAppId(String appId) {
        return lookup(key -> get(BASE + "/games/lookup/v1?key={key}&appid={v}", key, appId));
    }

    @Override
    public Optional<ItadGame> lookupByTitle(String title) {
        return lookup(key -> get(BASE + "/games/lookup/v1?key={key}&title={v}", key, title));
    }

    @Override
    public Optional<ItadGame> info(String itadId) {
        return call(key -> get(BASE + "/games/info/v2?key={key}&id={v}", key, itadId))
                .filter(json -> json.hasNonNull("id") && json.hasNonNull("slug"))
                .map(json -> new ItadGame(json.get("id").asString(), json.get("slug").asString(),
                        json.path("title").asString(null)));
    }

    @Override
    public Optional<GamePrices> prices(ItadGame game, String country) {
        String ids = "[\"" + game.id() + "\"]";
        JsonNode priced = first(call(key -> post(
                BASE + "/games/prices/v3?key={key}&country={v}&deals=false", key, country, ids)));
        JsonNode low = first(call(key -> post(
                BASE + "/games/historylow/v1?key={key}&country={v}", key, country, ids)));

        List<GamePrices.Deal> deals = new ArrayList<>();
        String currency = null;
        if (priced != null) {
            for (JsonNode d : priced.path("deals")) {
                BigDecimal price = decimal(d.path("price").path("amount"));
                if (price == null) {
                    continue;
                }
                if (currency == null) {
                    currency = d.path("price").path("currency").asString(null);
                }
                deals.add(new GamePrices.Deal(d.path("shop").path("id").asString(null),
                        d.path("shop").path("name").asString(null), price,
                        decimal(d.path("regular").path("amount")), d.path("cut").asInt(0),
                        d.path("url").asString(null)));
            }
        }
        GamePrices.HistoryLow historyLow = low == null ? null : historyLow(low);
        if (deals.isEmpty() && historyLow == null) {
            return Optional.empty();
        }
        if (currency == null && low != null) {
            currency = low.path("low").path("price").path("currency").asString(null);
        }
        deals.sort(Comparator.comparing(GamePrices.Deal::price));
        return Optional.of(new GamePrices(game.id(), country, currency, deals.isEmpty() ? null : deals.get(0),
                historyLow, List.copyOf(deals), itadUrl(game.slug())));
    }

    /** The game's IsThereAnyDeal page, null when the slug is unknown. */
    private static String itadUrl(String slug) {
        return slug == null || slug.isBlank() ? null : GAME_URL + slug + "/";
    }

    /** The first element of an array answer, null when absent or empty. */
    private static JsonNode first(Optional<JsonNode> json) {
        return json.filter(j -> j.isArray() && !j.isEmpty()).map(j -> j.get(0)).orElse(null);
    }

    private static GamePrices.HistoryLow historyLow(JsonNode entry) {
        JsonNode low = entry.path("low");
        BigDecimal price = decimal(low.path("price").path("amount"));
        if (price == null) {
            return null;
        }
        Instant date = null;
        String ts = low.path("timestamp").asString(null);
        if (ts != null) {
            try {
                date = OffsetDateTime.parse(ts).toInstant();
            } catch (RuntimeException e) {
                log.debug("Unreadable IsThereAnyDeal timestamp {}", ts);
            }
        }
        return new GamePrices.HistoryLow(low.path("shop").path("id").asString(null),
                low.path("shop").path("name").asString(null), price, date);
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }

    private String get(String uri, String key, String value) {
        return restClient.get().uri(uri, key, value).retrieve().body(String.class);
    }

    private String post(String uri, String key, String value, String body) {
        return restClient.post().uri(uri, key, value).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(String.class);
    }

    private Optional<ItadGame> lookup(Function<String, String> request) {
        return call(request)
                .filter(json -> json.path("found").asBoolean(false) && json.path("game").hasNonNull("id"))
                .map(json -> json.get("game"))
                .map(g -> new ItadGame(g.get("id").asString(), g.path("slug").asString(null),
                        g.path("title").asString(null)));
    }

    /** Runs {@code request} with the pool's next key; empty on 404 or an unreadable body; a 429 blocks the key and throws. */
    private Optional<JsonNode> call(Function<String, String> request) {
        ItadApiKeyRotator pool = rotator.orElseThrow(() -> new IllegalStateException("IsThereAnyDeal is not enabled"));
        String key = pool.nextKey().orElseThrow(() -> new IllegalStateException("no usable IsThereAnyDeal API key"));
        pool.onCall(key);
        try {
            String body = request.apply(key);
            if (body == null || body.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(mapper.readTree(body));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException.TooManyRequests e) {
            pool.block(key, retryAfter(e));
            throw e;
        } catch (JacksonException e) {
            log.warn("IsThereAnyDeal answer is not JSON: {}", e.getOriginalMessage());
            return Optional.empty();
        } catch (RestClientResponseException e) {
            // the key is a URL parameter and the original message may hold the URL or the body: neither is kept
            throw new IllegalStateException("IsThereAnyDeal request failed: HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new IllegalStateException("IsThereAnyDeal request failed: " + e.getClass().getSimpleName());
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
