package fr.enimaloc.catapult.apidocs;

import fr.enimaloc.catapult.MutableClock;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Timeout;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ApiDocsSpecTest {
    static final String SPEC = """
            {"openapi":"3.1.0","info":{"title":"OpenAPI definition","version":"v0"},\
            "servers":[{"url":"http://localhost:8080"}],"paths":{"/api/game/{uuid}":{}}}""";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path dir;
    MockWebServer server;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-27T09:30:00Z"));
    Path cacheFile;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        cacheFile = dir.resolve("data").resolve("api-docs-cache.json");
    }

    @AfterEach
    void tearDown() throws IOException {
        RequestContextHolder.resetRequestAttributes();
        server.shutdown();
    }

    ApiDocsSpec spec() {
        return spec(Duration.ofSeconds(5));
    }

    ApiDocsSpec spec(Duration timeout) {
        return new ApiDocsSpec(server.url("/").toString(), cacheFile.toString(), clock, timeout);
    }

    static MockResponse hung() {
        return ok(SPEC).setHeadersDelay(30, TimeUnit.SECONDS);
    }

    static MockResponse ok(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void fetchesAnonymously() throws InterruptedException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ApiClient.SESSION_JWT_KEY, "a.session.jwt");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        server.enqueue(ok(SPEC));

        assertThat(spec().current()).isPresent();

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded.getPath()).isEqualTo("/api/v3/api-docs");
        assertThat(recorded.getHeader("Authorization")).isNull();
        assertThat(recorded.getHeader("Cookie")).isNull();
    }

    @Test
    void rewritesServersToTheSite() {
        server.enqueue(ok(SPEC));

        ApiDocsSpec.Cached cached = spec().current().orElseThrow();

        JsonNode json = JSON.readTree(cached.json());
        assertThat(json.get("servers").toString()).isEqualTo("[{\"url\":\"/\"}]");
        assertThat(json.get("openapi").asString()).isEqualTo("3.1.0");
        assertThat(json.get("paths").has("/api/game/{uuid}")).isTrue();
        assertThat(cached.fromCache()).isFalse();
        assertThat(cached.fetchedAt()).isEqualTo(clock.now);
    }

    @Test
    void addsTheServersWhenTheApiGivesNone() {
        server.enqueue(ok("{\"openapi\":\"3.1.0\",\"paths\":{}}"));

        assertThat(JSON.readTree(spec().current().orElseThrow().json()).get("servers").toString())
                .isEqualTo("[{\"url\":\"/\"}]");
    }

    @Test
    void refetchesAtMostOncePerMinute() {
        ApiDocsSpec spec = spec();
        server.enqueue(ok(SPEC));
        server.enqueue(ok(SPEC.replace("v0", "v1")));

        spec.current();
        clock.advance(Duration.ofSeconds(59));
        spec.current();
        assertThat(server.getRequestCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        ApiDocsSpec.Cached refreshed = spec.current().orElseThrow();
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(refreshed.json()).contains("\"v1\"");
    }

    @Test
    void aFailedFetchIsNotRetriedWithinTheMinute() {
        ApiDocsSpec spec = spec();
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThat(spec.current()).isEmpty();
        clock.advance(Duration.ofSeconds(30));
        assertThat(spec.current()).isEmpty();

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void servesTheMemoryCopyWhenTheApiFails() {
        ApiDocsSpec spec = spec();
        server.enqueue(ok(SPEC));
        Instant fetchedAt = clock.now;
        spec.current();
        clock.advance(Duration.ofMinutes(2));
        server.enqueue(new MockResponse().setResponseCode(503));

        ApiDocsSpec.Cached cached = spec.current().orElseThrow();

        assertThat(cached.fromCache()).isTrue();
        assertThat(cached.fetchedAt()).isEqualTo(fetchedAt);
        assertThat(cached.json()).contains("\"servers\":[{\"url\":\"/\"}]");
    }

    @Test
    void anAnswerThatIsNoSpecCountsAsAFailure() {
        ApiDocsSpec spec = spec();
        server.enqueue(ok(SPEC));
        spec.current();
        clock.advance(Duration.ofMinutes(2));
        server.enqueue(ok("<html>maintenance</html>"));

        assertThat(spec.current()).get().extracting(ApiDocsSpec.Cached::fromCache).isEqualTo(true);
    }

    @Test
    void survivesARestartThroughTheDiskCopy() throws IOException {
        server.enqueue(ok(SPEC));
        Instant fetchedAt = clock.now;
        spec().current();
        assertThat(Files.exists(cacheFile)).isTrue();

        clock.advance(Duration.ofHours(3));
        server.shutdown();
        ApiDocsSpec restarted = spec();

        ApiDocsSpec.Cached cached = restarted.current().orElseThrow();
        assertThat(cached.fromCache()).isTrue();
        assertThat(cached.fetchedAt()).isEqualTo(fetchedAt);
        assertThat(JSON.readTree(cached.json()).get("servers").toString()).isEqualTo("[{\"url\":\"/\"}]");
    }

    @Test
    void ignoresACorruptDiskCopy() throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "{not json");
        server.shutdown();

        assertThat(spec().current()).isEmpty();
    }

    @Test
    void ignoresADiskCopyWithoutASpec() throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "{\"fetchedAt\":\"2026-10-27T09:30:00Z\"}");
        server.shutdown();

        assertThat(spec().current()).isEmpty();
    }

    @Test
    void anUnwritableDiskCopyKeepsTheMemoryOne() throws IOException {
        // A directory where the file should be: it can't be written, the spec is still served.
        Files.createDirectories(cacheFile);
        server.enqueue(ok(SPEC));

        assertThat(spec().current()).get().extracting(ApiDocsSpec.Cached::fromCache).isEqualTo(false);
    }

    @Test
    void ignoresADiskCopyThatIsNoOpenApiSpec() throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "{\"fetchedAt\":\"2026-10-27T09:30:00Z\",\"spec\":{\"paths\":{}}}");
        server.shutdown();

        assertThat(spec().current()).isEmpty();
    }

    @Test
    void aHungApiIsGivenUpOnAfterTheSpecsOwnTimeout() {
        server.enqueue(hung());
        long start = System.nanoTime();

        assertThat(spec(Duration.ofMillis(300)).current()).isEmpty();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @Timeout(10)
    void withoutACopy_concurrentViewersShareOneFetchOfAHungApi() throws Exception {
        ApiDocsSpec spec = spec(Duration.ofMillis(500));
        for (int i = 0; i < 8; i++) server.enqueue(hung());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            long start = System.nanoTime();
            List<Future<Optional<ApiDocsSpec.Cached>>> viewers = new ArrayList<>();
            for (int i = 0; i < 8; i++) viewers.add(pool.submit(spec::current));
            for (Future<Optional<ApiDocsSpec.Cached>> viewer : viewers) assertThat(viewer.get()).isEmpty();

            // One fetch, given up on after the timeout: the others waited for it, none tried again.
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(server.getRequestCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void withACopy_viewersGetItAtOnceWhileAHungApiIsAsked() throws Exception {
        ApiDocsSpec spec = spec(Duration.ofSeconds(3));
        server.enqueue(ok(SPEC));
        spec.current();
        clock.advance(Duration.ofMinutes(2));
        server.enqueue(hung());

        CompletableFuture<Optional<ApiDocsSpec.Cached>> fetching = CompletableFuture.supplyAsync(spec::current);
        server.takeRequest(2, TimeUnit.SECONDS);
        // The hung fetch has reached catapult-api: the lock is held.
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        long start = System.nanoTime();
        Optional<ApiDocsSpec.Cached> meanwhile = spec.current();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
        assertThat(meanwhile).get().extracting(ApiDocsSpec.Cached::fromCache).isEqualTo(false);
        assertThat(fetching.get()).get().extracting(ApiDocsSpec.Cached::fromCache).isEqualTo(true);
    }

    @Test
    @Timeout(10)
    void withoutACopy_aViewerArrivingDuringTheFirstFetchWaitsForIt() throws Exception {
        ApiDocsSpec spec = spec();
        server.enqueue(ok(SPEC).setHeadersDelay(1, TimeUnit.SECONDS));

        CompletableFuture<Optional<ApiDocsSpec.Cached>> first = CompletableFuture.supplyAsync(spec::current);
        // The slow fetch has reached catapult-api: the second viewer comes while it is in flight.
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        Optional<ApiDocsSpec.Cached> second = spec.current();

        assertThat(second).get().extracting(ApiDocsSpec.Cached::fromCache).isEqualTo(false);
        assertThat(first.get()).isPresent();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void emptyWhenNothingWasEverFetched() throws IOException {
        server.shutdown();

        assertThat(spec().current()).isEmpty();
    }
}
