package fr.enimaloc.catapult.service.wellknown;

import org.junit.jupiter.api.Test;
import java.util.Optional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaStoreRegistryTest {
    static final String CATALOG = """
            {"schemas":[
              {"name":"gpc","fileMatch":["**/.well-known/gpc.json"],"url":"https://www.schemastore.org/gpc.json"},
              {"name":"Google Digital Assetlinks","fileMatch":["assetlinks.json"],"url":"https://www.schemastore.org/assetlinks.json"},
              {"name":"insecure","fileMatch":["**/.well-known/insecure.json"],"url":"http://example.org/s.json"}
            ]}""";

    static class MovableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-16T10:00:00Z"));
        void advance(Duration d) { now.set(now.get().plus(d)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    final List<String> fetched = new ArrayList<>();

    static byte[] bytes(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    Function<String, byte[]> web(boolean up) {
        return url -> {
            fetched.add(url);
            if (!up) throw new IllegalStateException("offline");
            if (url.endsWith("catalog.json")) return bytes(CATALOG);
            return bytes("{\"title\":\"" + url + "\"}");
        };
    }

    static String catalogOf(String key, String url) {
        return "{\"schemas\":[{\"name\":\"n\",\"fileMatch\":[\"" + key + "\"],\"url\":\"" + url + "\"}]}";
    }

    /** A JSON document of exactly {@code size} bytes (ASCII padding). */
    static byte[] json(int size) {
        byte[] b = new byte[size];
        java.util.Arrays.fill(b, (byte) ' ');
        b[0] = '{';
        b[size - 1] = '}';
        return b;
    }

    @Test
    void findsASchemaByWellKnownFileMatchOrBareName() {
        var registry = new SchemaStoreRegistry(web(true), new MovableClock());
        assertThat(registry.schemaFor("gpc.json")).hasValueSatisfying(s -> assertThat(s).contains("gpc.json"));
        assertThat(registry.schemaFor("assetlinks.json")).hasValueSatisfying(s -> assertThat(s).contains("assetlinks.json"));
        assertThat(registry.schemaFor("security.txt")).isEmpty();
    }

    @Test
    void neverFetchesANonHttpsSchema() {
        var registry = new SchemaStoreRegistry(web(true), new MovableClock());
        assertThat(registry.schemaFor("insecure.json")).isEmpty();
        assertThat(fetched).noneMatch(u -> u.startsWith("http://"));
    }

    @Test
    void cachesCatalogAndSchemasFor24h() {
        MovableClock clock = new MovableClock();
        var registry = new SchemaStoreRegistry(web(true), clock);
        registry.schemaFor("gpc.json");
        registry.schemaFor("gpc.json");
        assertThat(fetched).hasSize(2); // catalog + schema
        clock.advance(Duration.ofHours(25));
        registry.schemaFor("gpc.json");
        assertThat(fetched).hasSize(4);
    }

    @Test
    void fallsBackToTheBundledSnapshot_andRetriesAfter10Minutes() {
        MovableClock clock = new MovableClock();
        var registry = new SchemaStoreRegistry(web(false), clock);
        assertThat(registry.schemaFor("assetlinks.json")).isPresent(); // bundled
        int calls = fetched.size();
        clock.advance(Duration.ofMinutes(5));
        registry.schemaFor("assetlinks.json");
        assertThat(fetched).hasSize(calls);
        clock.advance(Duration.ofMinutes(6));
        registry.schemaFor("assetlinks.json");
        assertThat(fetched.size()).isGreaterThan(calls);
    }

    Function<String, byte[]> serving(String catalog, java.util.function.Supplier<byte[]> schema) {
        return url -> {
            fetched.add(url);
            return url.endsWith("catalog.json") ? bytes(catalog) : schema.get();
        };
    }

    @Test
    void acceptsExactly1MiBAndRefusesOneByteMore() {
        var ok = new SchemaStoreRegistry(serving(CATALOG, () -> json(1024 * 1024)), new MovableClock());
        assertThat(ok.schemaFor("gpc.json")).isPresent();
        var tooBig = new SchemaStoreRegistry(serving(CATALOG, () -> json(1024 * 1024 + 1)), new MovableClock());
        assertThat(tooBig.schemaFor("gpc.json")).isEmpty();
    }

    @Test
    void countsBytesNotChars() {
        // 600_000 two-byte chars: < 1 MiB chars, > 1 MiB bytes
        byte[] body = bytes("{\"a\":\"" + "é".repeat(600_000) + "\"}");
        var registry = new SchemaStoreRegistry(serving(CATALOG, () -> body), new MovableClock());
        assertThat(registry.schemaFor("gpc.json")).isEmpty();
    }

    @Test
    void onlyFetchesSchemaStoreHosts() {
        for (String url : List.of("https://169.254.169.254/x.json", "https://evil.example/x.json",
                "https://user@www.schemastore.org/x.json", "https://www.schemastore.org:8443/x.json")) {
            fetched.clear();
            var registry = new SchemaStoreRegistry(serving(catalogOf("**/.well-known/gpc.json", url), () -> bytes("{}")),
                    new MovableClock());
            assertThat(registry.schemaFor("gpc.json")).as(url).isEmpty();
            assertThat(fetched).as(url).containsExactly(SchemaStoreRegistry.CATALOG_URL);
        }
        var registry = new SchemaStoreRegistry(serving(catalogOf("**/.well-known/gpc.json",
                "https://raw.githubusercontent.com/x/y.json"), () -> bytes("{}")), new MovableClock());
        assertThat(registry.schemaFor("gpc.json")).isPresent();
    }

    @Test
    void aFailedSchemaDownloadIsNotRetriedBefore10Minutes_andTheSnapshotAnswers() {
        MovableClock clock = new MovableClock();
        var registry = new SchemaStoreRegistry(url -> {
            fetched.add(url);
            if (url.endsWith("catalog.json")) return bytes(CATALOG);
            throw new IllegalStateException("boom");
        }, clock);
        assertThat(registry.schemaFor("assetlinks.json")).isPresent(); // bundled
        assertThat(fetched).hasSize(2);
        clock.advance(Duration.ofMinutes(5));
        assertThat(registry.schemaFor("assetlinks.json")).isPresent();
        assertThat(fetched).hasSize(2);
        clock.advance(Duration.ofMinutes(6));
        registry.schemaFor("assetlinks.json");
        assertThat(fetched).hasSize(3);
    }

    @Test
    void aBodyThatIsNotJsonIsUnavailable_andNotRetriedBefore10Minutes() {
        MovableClock clock = new MovableClock();
        var registry = new SchemaStoreRegistry(serving(CATALOG, () -> bytes("<html>oops</html>")), clock);
        assertThat(registry.schemaFor("assetlinks.json")).isPresent();
        assertThat(fetched).hasSize(2);
        clock.advance(Duration.ofMinutes(5));
        assertThat(registry.schemaFor("assetlinks.json")).isPresent();
        assertThat(fetched).hasSize(2);
        clock.advance(Duration.ofMinutes(6));
        registry.schemaFor("assetlinks.json");
        assertThat(fetched).hasSize(3);
    }

    @Test
    void theBareNameOnlyStandsForASingleSegmentPath() {
        var registry = new SchemaStoreRegistry(serving(catalogOf("package.json", "https://www.schemastore.org/package.json"),
                () -> bytes("{}")), new MovableClock());
        assertThat(registry.schemaFor("foo/package.json")).isEmpty();
        assertThat(registry.schemaFor("package.json")).isPresent();
        assertThat(new SchemaStoreRegistry(web(true), new MovableClock()).schemaFor("assetlinks.json")).isPresent();
    }

    @Test
    void concurrentRequestsShareOneDownload_madeOutsideTheLock() throws Exception {
        java.util.concurrent.CountDownLatch inDownload = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger schemaDownloads = new java.util.concurrent.atomic.AtomicInteger();
        var registry = new SchemaStoreRegistry(url -> {
            if (url.endsWith("catalog.json")) return bytes(CATALOG);
            schemaDownloads.incrementAndGet();
            inDownload.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return bytes("{\"title\":\"gpc\"}");
        }, new MovableClock());
        registry.schemaFor("unknown.json"); // loads the catalog first

        AtomicReference<Optional<String>> first = new AtomicReference<>();
        AtomicReference<Optional<String>> second = new AtomicReference<>();
        Thread a = new Thread(() -> first.set(registry.schemaFor("gpc.json")));
        a.start();
        assertThat(inDownload.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // The registry isn't locked during the download: another schema's cached answer is served
        assertThat(registry.schemaFor("security.txt")).isEmpty();
        Thread b = new Thread(() -> second.set(registry.schemaFor("gpc.json")));
        b.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (b.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        release.countDown();
        a.join(5_000);
        b.join(5_000);

        assertThat(schemaDownloads).hasValue(1);
        assertThat(first.get()).hasValue("{\"title\":\"gpc\"}");
        assertThat(second.get()).hasValue("{\"title\":\"gpc\"}");
    }
}
