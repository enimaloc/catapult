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
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;

/**
 * The JSON Schema SchemaStore publishes for a well-known path, for the admin's generated forms and
 * save-time checks: SchemaStore's catalog and the schemas it points to are kept 24 h; when SchemaStore
 * can't be reached, the copies bundled with catapult-api, SchemaStore being asked again 10 min later at
 * the earliest. Only https schemas of at most 1 MiB, on SchemaStore's own hosts and without redirects, are fetched.
 */
@Slf4j
@Service
public class SchemaStoreRegistry {
    static final String CATALOG_URL = "https://www.schemastore.org/api/json/catalog.json";
    static final Duration TTL = Duration.ofHours(24);
    static final Duration RETRY = Duration.ofMinutes(10);
    static final int MAX_SCHEMA = 1024 * 1024;
    static final int MAX_CATALOG = 8 * 1024 * 1024;
    private static final Set<String> HOSTS = Set.of("www.schemastore.org", "json.schemastore.org", "raw.githubusercontent.com");
    private static final String SNAPSHOT = "schemastore/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Cached(String value, Instant until, boolean failed) {}

    /** Raw bytes of a url, at most the cap + 1 of them (the registry refuses anything over its cap); throws when unavailable. */
    private final Function<String, byte[]> download;
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

    SchemaStoreRegistry(Function<String, byte[]> download, Clock clock) {
        this.download = download;
        this.clock = clock;
    }

    private static Function<String, byte[]> downloader(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false); // a 3xx is "unavailable"
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        RestClient client = builder.clone().requestFactory(factory).build();
        return url -> {
            int limit = (CATALOG_URL.equals(url) ? MAX_CATALOG : MAX_SCHEMA) + 1;
            return client.get().uri(url).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("HTTP " + response.getStatusCode().value());
                }
                return response.getBody().readNBytes(limit);
            });
        };
    }

    /** Only SchemaStore's own hosts are fetched: https, no userinfo, default port. */
    static boolean allowed(String url) {
        try {
            URI uri = new URI(url);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                    && uri.getHost() != null && HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (URISyntaxException | RuntimeException e) {
            return false;
        }
    }

    public synchronized Optional<String> schemaFor(String path) {
        if (path == null || path.isBlank()) return Optional.empty();
        Instant now = clock.instant();
        Map<String, String> live = liveCatalog(now);
        if (live == null) return snapshot(path);
        String url = match(live, path);
        if (url == null || !allowed(url)) return Optional.empty();
        Cached cached = schemas.get(url);
        if (cached != null && now.isBefore(cached.until())) {
            if (cached.value() != null) return Optional.of(cached.value());
            return cached.failed() ? snapshot(path) : Optional.empty();
        }
        try {
            byte[] body = download.apply(url);
            String schema = null;
            if (body != null && body.length <= MAX_SCHEMA) {
                String text = new String(body, StandardCharsets.UTF_8);
                JSON.readTree(text); // not JSON (an error page served with 200): unavailable
                schema = text;
            }
            if (schema != null) {
                schemas.put(url, new Cached(schema, now.plus(TTL), false));
                return Optional.of(schema);
            }
            log.warn("SchemaStore schema {} refused: over {} bytes", url, MAX_SCHEMA);
            schemas.put(url, new Cached(null, now.plus(RETRY), false));
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("SchemaStore schema {} unavailable: {}", url, e.getMessage());
            schemas.put(url, new Cached(null, now.plus(RETRY), true));
            return snapshot(path);
        }
    }

    /** The live catalog, null while SchemaStore is unreachable (until the retry delay is over). */
    private Map<String, String> liveCatalog(Instant now) {
        if (catalog != null && now.isBefore(catalogUntil)) return catalog;
        if (now.isBefore(retryAt)) return catalog;
        try {
            byte[] raw = download.apply(CATALOG_URL);
            if (raw == null || raw.length > MAX_CATALOG) throw new IllegalStateException("catalog over " + MAX_CATALOG + " bytes");
            JsonNode root = JSON.readTree(new String(raw, StandardCharsets.UTF_8));
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
        // the bare name only stands for a single-segment path (foo/package.json is not package.json)
        String[] keys = path.indexOf('/') < 0
                ? new String[]{"**/.well-known/" + path, ".well-known/" + path, path}
                : new String[]{"**/.well-known/" + path, ".well-known/" + path};
        for (String key : keys) {
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
