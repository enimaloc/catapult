package fr.enimaloc.catapult.service.wellknown;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The JSON Schema SchemaStore publishes for a well-known path, for the admin's generated forms and
 * save-time checks: SchemaStore's catalog and the schemas it points to are kept 24 h; when SchemaStore
 * can't be reached, the copies bundled with catapult-api, SchemaStore being asked again 10 min later at
 * the earliest. Only https schemas of at most 1 MiB are fetched.
 */
@Slf4j
@Service
public class SchemaStoreRegistry {
    static final String CATALOG_URL = "https://www.schemastore.org/api/json/catalog.json";
    static final Duration TTL = Duration.ofHours(24);
    static final Duration RETRY = Duration.ofMinutes(10);
    static final int MAX_SCHEMA = 1024 * 1024;
    private static final String SNAPSHOT = "schemastore/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Cached(String value, Instant until) {}

    private final Function<String, String> download;
    private final Clock clock;
    private Map<String, String> catalog; // fileMatch → schema url
    private Instant catalogUntil = Instant.MIN;
    private Instant retryAt = Instant.MIN;
    private final Map<String, Cached> schemas = new HashMap<>();
    private Map<String, String> snapshotIndex;

    @Autowired
    public SchemaStoreRegistry(RestClient.Builder builder) {
        this(downloader(builder), Clock.systemUTC());
    }

    SchemaStoreRegistry(Function<String, String> download, Clock clock) {
        this.download = download;
        this.clock = clock;
    }

    private static Function<String, String> downloader(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        RestClient client = builder.clone().requestFactory(factory).build();
        return url -> client.get().uri(url).retrieve().body(String.class);
    }

    public synchronized Optional<String> schemaFor(String path) {
        if (path == null || path.isBlank()) return Optional.empty();
        Instant now = clock.instant();
        Map<String, String> live = liveCatalog(now);
        if (live == null) return snapshot(path);
        String url = match(live, path);
        if (url == null || !url.startsWith("https://")) return Optional.empty();
        Cached cached = schemas.get(url);
        if (cached != null && now.isBefore(cached.until())) return Optional.ofNullable(cached.value());
        try {
            String body = download.apply(url);
            String schema = body == null || body.length() > MAX_SCHEMA ? null : body;
            schemas.put(url, new Cached(schema, now.plus(TTL)));
            return Optional.ofNullable(schema);
        } catch (RuntimeException e) {
            log.warn("SchemaStore schema {} unavailable: {}", url, e.getMessage());
            return snapshot(path);
        }
    }

    /** The live catalog, null while SchemaStore is unreachable (until the retry delay is over). */
    private Map<String, String> liveCatalog(Instant now) {
        if (catalog != null && now.isBefore(catalogUntil)) return catalog;
        if (now.isBefore(retryAt)) return catalog;
        try {
            JsonNode root = JSON.readTree(download.apply(CATALOG_URL));
            Map<String, String> index = new LinkedHashMap<>();
            for (JsonNode schema : root.path("schemas")) {
                String url = schema.path("url").asString("");
                for (JsonNode match : schema.path("fileMatch")) index.putIfAbsent(match.asString(""), url);
            }
            catalog = index;
            catalogUntil = now.plus(TTL);
            return catalog;
        } catch (RuntimeException e) {
            log.warn("SchemaStore catalog unavailable, using the bundled copies: {}", e.getMessage());
            retryAt = now.plus(RETRY);
            return catalog;
        }
    }

    /** {@code **}/.well-known/<path>, .well-known/<path>, then the path's last segment. */
    private static String match(Map<String, String> index, String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        for (String key : new String[]{"**/.well-known/" + path, ".well-known/" + path, last}) {
            String url = index.get(key);
            if (url != null) return url;
        }
        return null;
    }

    private Optional<String> snapshot(String path) {
        try {
            if (snapshotIndex == null) {
                String json = new ClassPathResource(SNAPSHOT + "index.json").getContentAsString(StandardCharsets.UTF_8);
                Map<String, String> index = new LinkedHashMap<>();
                JSON.readTree(json).properties().forEach(e -> index.put(e.getKey(), e.getValue().asString("")));
                snapshotIndex = index;
            }
            String file = match(snapshotIndex, path);
            if (file == null) return Optional.empty();
            return Optional.of(new ClassPathResource(SNAPSHOT + file).getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.error("Bundled SchemaStore copy unreadable", e);
            return Optional.empty();
        }
    }
}
