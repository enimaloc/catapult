package fr.enimaloc.catapult.service.wellknown;

import org.junit.jupiter.api.Test;

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

    Function<String, String> web(boolean up) {
        return url -> {
            fetched.add(url);
            if (!up) throw new IllegalStateException("offline");
            if (url.endsWith("catalog.json")) return CATALOG;
            return "{\"title\":\"" + url + "\"}";
        };
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

    @Test
    void refusesAnOversizedSchema() {
        var registry = new SchemaStoreRegistry(url -> url.endsWith("catalog.json") ? CATALOG : "x".repeat(1024 * 1024 + 1),
                new MovableClock());
        assertThat(registry.schemaFor("gpc.json")).isEmpty();
    }
}
