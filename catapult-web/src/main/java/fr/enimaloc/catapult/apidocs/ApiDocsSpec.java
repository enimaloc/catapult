package fr.enimaloc.catapult.apidocs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * catapult-api's OpenAPI spec, for the site's documentation page (ApiDocsController): asked anonymously (it is
 * public: no session, no bearer) with its own short timeout, so a hung catapult-api holds a page {@value
 * #TIMEOUT_SECONDS} s at most, and at most once a minute, its {@code servers} rewritten to the site itself ({@code "/"}), so
 * that "Try it out" goes through catapult-web, which relays the documented paths. The last valid copy is kept in
 * memory and on disk ({@code catapult.api-docs.cache-file}): while catapult-api fails, even after a restart, the page
 * still shows it, marked as a cached copy. A disk copy that can't be read or written is logged and ignored.
 */
@Slf4j
@Component
public class ApiDocsSpec {
    static final Duration REFRESH = Duration.ofSeconds(60);
    static final long TIMEOUT_SECONDS = 5;
    static final String SPEC_PATH = "/api/v3/api-docs";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The spec as served ({@code json}), when it was fetched, and whether it is an older copy than the last try. */
    public record Cached(String json, Instant fetchedAt, boolean fromCache) {}

    private final HttpClient http;
    private final URI specUri;
    private final Duration timeout;
    private final Path cacheFile;
    private final Clock clock;
    private final ReentrantLock loading = new ReentrantLock();
    /** What {@link #current()} answers until the next fetch; null until a copy was ever had. */
    private volatile Cached current;
    private volatile Instant nextFetch = Instant.MIN;
    private boolean diskRead;

    @Autowired
    public ApiDocsSpec(@Value("${catapult.backend-url}") String apiUrl,
                       @Value("${catapult.api-docs.cache-file:./data/api-docs-cache.json}") String cacheFile) {
        this(apiUrl, cacheFile, Clock.systemUTC(), Duration.ofSeconds(TIMEOUT_SECONDS));
    }

    ApiDocsSpec(String apiUrl, String cacheFile, Clock clock, Duration timeout) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
        this.specUri = URI.create(apiUrl.replaceAll("/+$", "") + SPEC_PATH);
        this.timeout = timeout;
        this.cacheFile = Path.of(cacheFile);
        this.clock = clock;
    }

    /**
     * The spec, fetched again when the last try is a minute old; the last valid copy when catapult-api fails
     * ({@code fromCache}); empty when none was ever had. While one visitor's page fetches it, the others get the
     * copy already had (they wait for that fetch when there is none). The next try is only scheduled once the fetch
     * is over, so a visitor arriving during the very first one waits for it rather than finding nothing.
     */
    public Optional<Cached> current() {
        if (clock.instant().isBefore(nextFetch)) return Optional.ofNullable(current);
        if (current == null) loading.lock();
        else if (!loading.tryLock()) return Optional.of(current);
        try {
            Instant now = clock.instant();
            if (now.isBefore(nextFetch)) return Optional.ofNullable(current);
            String fetched = fetch();
            if (fetched != null) {
                current = new Cached(fetched, now, false);
                write(current);
            } else {
                Cached kept = current != null ? current : readDisk();
                current = kept == null ? null : new Cached(kept.json(), kept.fetchedAt(), true);
            }
            nextFetch = now.plus(REFRESH);
            return Optional.ofNullable(current);
        } finally {
            loading.unlock();
        }
    }

    /** catapult-api's spec, its servers the site's; null when it answers none or something that is not one. */
    private String fetch() {
        HttpResponse<String> answer;
        try {
            HttpRequest request = HttpRequest.newBuilder(specUri).timeout(timeout).GET().build();
            answer = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            log.warn("catapult-api's spec unavailable: {}", e.toString());
            return null;
        }
        if (answer.statusCode() != 200) {
            log.warn("catapult-api's spec unavailable: HTTP {}", answer.statusCode());
            return null;
        }
        try {
            if (!(JSON.readTree(answer.body()) instanceof ObjectNode spec) || !spec.has("openapi")) {
                log.warn("catapult-api answered something that is not an OpenAPI spec");
                return null;
            }
            spec.putArray("servers").addObject().put("url", "/");
            return JSON.writeValueAsString(spec);
        } catch (RuntimeException e) {
            log.warn("catapult-api's spec is unreadable: {}", e.toString());
            return null;
        }
    }

    private void write(Cached copy) {
        try {
            ObjectNode file = JSON.createObjectNode();
            file.put("fetchedAt", copy.fetchedAt().toString());
            file.set("spec", JSON.readTree(copy.json()));
            Path parent = cacheFile.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "api-docs", ".tmp");
            try {
                Files.writeString(temporary, JSON.writeValueAsString(file), StandardCharsets.UTF_8);
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            diskRead = true;
        } catch (IOException | RuntimeException e) {
            log.warn("API docs cache {} not written: {}", cacheFile, e.toString());
        }
    }

    /** The disk copy, read once (a later one is the memory's); null when there is none or it is unreadable. */
    private Cached readDisk() {
        if (diskRead) return null;
        diskRead = true;
        if (!Files.isRegularFile(cacheFile)) return null;
        try {
            JsonNode file = JSON.readTree(Files.readString(cacheFile, StandardCharsets.UTF_8));
            JsonNode spec = file.get("spec");
            JsonNode fetchedAt = file.get("fetchedAt");
            if (spec == null || !spec.isObject() || !spec.has("openapi") || fetchedAt == null || !fetchedAt.isString()) {
                log.warn("API docs cache {} ignored: no spec in it", cacheFile);
                return null;
            }
            return new Cached(JSON.writeValueAsString(spec), Instant.parse(fetchedAt.asString()), true);
        } catch (IOException | RuntimeException e) {
            log.warn("API docs cache {} ignored: {}", cacheFile, e.toString());
            return null;
        }
    }
}
