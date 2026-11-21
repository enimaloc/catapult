package fr.enimaloc.catapult.service.enrichment.steamgriddb;

import fr.enimaloc.catapult.getter.steamgriddb.SteamGridDbApiKeyRotator;
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
import java.util.Optional;

/**
 * {@link SteamGridDbClient} over the SteamGridDB API v2. Each request takes the next key of the shared pool; a 429
 * blocks that key and is retried once with the next one. Without a usable key (feature disabled, empty pool)
 * nothing is sent and {@link SteamGridDbUnavailableException} is thrown, so it is never cached as "game absent".
 */
@Slf4j
@Component
public class HttpSteamGridDbClient implements SteamGridDbClient {

    private static final String BASE = "https://www.steamgriddb.com/api/v2";
    private static final String GAME_URL = "https://www.steamgriddb.com/game/";
    private static final Duration DEFAULT_BLOCK = Duration.ofSeconds(60);

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final Optional<SteamGridDbApiKeyRotator> rotator;

    public HttpSteamGridDbClient(RestClient.Builder builder, Optional<SteamGridDbApiKeyRotator> rotator) {
        this.restClient = builder.build();
        this.rotator = rotator;
    }

    @Override
    public boolean isConfigured() {
        return rotator.map(SteamGridDbApiKeyRotator::hasKeys).orElse(false);
    }

    @Override
    public Optional<SteamGridDbAssets> fetchBySteamAppId(String steamAppId) {
        return get("/games/steam/{id}", steamAppId)
                .map(json -> json.path("data"))
                .filter(data -> data.hasNonNull("id"))
                .flatMap(data -> fetchAssets(data.get("id").asLong(), data.path("name").asString(null)));
    }

    @Override
    public Optional<SteamGridDbAssets> fetchByName(String name) {
        return get("/search/autocomplete/{term}", name)
                .map(json -> json.path("data"))
                .filter(data -> data.isArray() && !data.isEmpty() && data.get(0).hasNonNull("id"))
                .flatMap(data -> fetchAssets(data.get(0).get("id").asLong(), data.get(0).path("name").asString(null)));
    }

    @Override
    public Optional<SteamGridDbAssets> fetchByGameId(long gameId, String name) {
        if (name != null) {
            return fetchAssets(gameId, name);
        }
        // the name comes with the game: an unknown id is absent rather than a nameless game without artwork
        return get("/games/id/{id}", gameId)
                .map(json -> json.path("data"))
                .filter(data -> data.hasNonNull("id"))
                .flatMap(data -> fetchAssets(gameId, data.path("name").asString(null)));
    }

    private Optional<SteamGridDbAssets> fetchAssets(long gameId, String name) {
        List<SteamGridDbAssets.Asset> grids = assets("grids", gameId).orElse(List.of());
        List<SteamGridDbAssets.Asset> heroes = assets("heroes", gameId).orElse(List.of());
        List<SteamGridDbAssets.Asset> logos = assets("logos", gameId).orElse(List.of());
        List<SteamGridDbAssets.Asset> icons = assets("icons", gameId).orElse(List.of());
        return Optional.of(new SteamGridDbAssets(gameId, name, GAME_URL + gameId, grids, heroes, logos, icons));
    }

    private Optional<List<SteamGridDbAssets.Asset>> assets(String kind, long gameId) {
        return get("/" + kind + "/game/{id}", gameId).map(json -> {
            List<SteamGridDbAssets.Asset> out = new ArrayList<>();
            for (JsonNode node : json.path("data")) {
                if (node.hasNonNull("url")) {
                    out.add(new SteamGridDbAssets.Asset(node.get("url").asString(),
                            node.path("thumb").asString(null), node.path("width").asInt(0),
                            node.path("height").asInt(0), node.path("style").asString(null)));
                }
            }
            return out;
        });
    }

    /** GET with the pool's next key; throws when no key is usable; empty on 404 or unreadable body; retries once on 429. */
    private Optional<JsonNode> get(String path, Object variable) {
        if (rotator.isEmpty()) {
            throw new SteamGridDbUnavailableException("SteamGridDB is not enabled");
        }
        SteamGridDbApiKeyRotator pool = rotator.get();
        for (int attempt = 0; ; attempt++) {
            Optional<String> key = pool.nextKey();
            if (key.isEmpty()) {
                throw new SteamGridDbUnavailableException("no usable SteamGridDB API key");
            }
            pool.onCall(key.get());
            try {
                String body = restClient.get().uri(BASE + path, variable)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.get())
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
            JsonNode json = mapper.readTree(body);
            return json.isObject() && json.path("success").asBoolean(false) ? Optional.of(json) : Optional.empty();
        } catch (JacksonException e) {
            log.warn("SteamGridDB answer is not JSON: {}", e.getOriginalMessage());
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
