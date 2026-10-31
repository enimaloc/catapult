package fr.enimaloc.catapult.service.wellknown;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class IanaWellKnownRegistryTest {
    static final String CSV = """
            URI Suffix,Reference,Status,Change Controller,Related Information,Date Registered,Date Modified
            acme-challenge,"[RFC 8555, Section 8.3]",permanent,IETF,,2016-08-18,
            assetlinks.json,[https://github.com/google/digitalassetlinks],permanent,[Google_Inc.],,2015-09-28,
            quoted,"say \"\"hi\"\"",provisional,X,,2020-01-01,
            """;

    /** A clock the test moves forward by hand. */
    static class MovableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-16T10:00:00Z"));
        void advance(Duration d) { now.set(now.get().plus(d)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @Test
    void parsesQuotedFieldsAndStripsReferenceBrackets() {
        var entries = IanaWellKnownRegistry.parse(CSV);
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).suffix()).isEqualTo("acme-challenge");
        assertThat(entries.get(0).reference()).isEqualTo("RFC 8555, Section 8.3");
        assertThat(entries.get(1).changeController()).isEqualTo("Google_Inc.");
        assertThat(entries.get(2).reference()).isEqualTo("say \"hi\"");
    }

    @Test
    void cachesTheLiveRegistryFor24h() {
        AtomicInteger downloads = new AtomicInteger();
        MovableClock clock = new MovableClock();
        var registry = new IanaWellKnownRegistry(() -> { downloads.incrementAndGet(); return CSV; }, clock);

        assertThat(registry.registry().source()).isEqualTo("LIVE");
        clock.advance(Duration.ofHours(23));
        registry.registry();
        assertThat(downloads).hasValue(1);

        clock.advance(Duration.ofHours(2));
        registry.registry();
        assertThat(downloads).hasValue(2);
    }

    @Test
    void fallsBackToTheSnapshot_andRetriesOnlyAfter10Minutes() {
        AtomicInteger downloads = new AtomicInteger();
        MovableClock clock = new MovableClock();
        Supplier<String> down = () -> { downloads.incrementAndGet(); throw new IllegalStateException("offline"); };
        var registry = new IanaWellKnownRegistry(down, clock);

        var first = registry.registry();
        assertThat(first.source()).isEqualTo("SNAPSHOT");
        assertThat(first.entries()).extracting("suffix").contains("security.txt", "acme-challenge");

        clock.advance(Duration.ofMinutes(5));
        registry.registry();
        assertThat(downloads).hasValue(1);

        clock.advance(Duration.ofMinutes(6));
        registry.registry();
        assertThat(downloads).hasValue(2);
    }

    @Test
    void anUnparseableAnswerAlsoFallsBack() {
        var registry = new IanaWellKnownRegistry(() -> "<html>maintenance</html>", new MovableClock());
        assertThat(registry.registry().source()).isEqualTo("SNAPSHOT");
    }

    @Test
    void theDownloaderRefusesARedirect_andAnOversizedAnswer() throws Exception {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("x".repeat(IanaWellKnownRegistry.MAX_BYTES + 1)));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody(CSV));
            server.start();
            Supplier<String> download = IanaWellKnownRegistry.downloader(
                    org.springframework.web.client.RestClient.builder(), server.url("/registry.csv").toString());

            org.assertj.core.api.Assertions.assertThatThrownBy(download::get).hasMessageContaining("302");
            org.assertj.core.api.Assertions.assertThatThrownBy(download::get).hasMessageContaining("bytes");
            assertThat(download.get()).isEqualTo(CSV);
            assertThat(server.getRequestCount()).isEqualTo(3); // the redirect was never followed
        }
    }

    /** Like every RestClient, the downloader's carries the trace interceptor: with TRACE on, the CSV still reaches it. */
    @Test
    void theDownloaderReadsTheAnswer_withTheTraceLogOn() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                fr.enimaloc.catapult.config.HttpTraceLoggingInterceptor.class);
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody(CSV));
            server.start();
            var builder = org.springframework.web.client.RestClient.builder()
                    .requestInterceptor(new fr.enimaloc.catapult.config.HttpTraceLoggingInterceptor());

            assertThat(IanaWellKnownRegistry.downloader(builder, server.url("/registry.csv").toString()).get())
                    .isEqualTo(CSV);
        } finally {
            logger.setLevel(previous);
        }
    }
}
