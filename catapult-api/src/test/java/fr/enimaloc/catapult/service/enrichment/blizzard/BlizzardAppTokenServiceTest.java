package fr.enimaloc.catapult.service.enrichment.blizzard;

import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BlizzardAppTokenServiceTest {

    private static final String URL = "https://oauth.battle.net/token";

    private MockRestServiceServer server;
    private RestClient.Builder builder;
    private final AtomicInteger acquired = new AtomicInteger();
    private BlizzardRateLimiter limiter;
    private MutableClock clock;

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-22T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        limiter = mock(BlizzardRateLimiter.class);
        when(limiter.budget()).thenReturn((FetchBudget) () -> {
            acquired.incrementAndGet();
            return true;
        });
        clock = new MutableClock();
    }

    private BlizzardAppTokenService service(String id, String secret) {
        return new BlizzardAppTokenService(builder.build(), limiter, id, secret, clock);
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/enrichment/blizzard/" + name), StandardCharsets.UTF_8);
    }

    @Test
    void tokenIsReusedUntilAnHourBeforeExpiry() throws IOException {
        server.expect(times(2), requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("token.json"), MediaType.APPLICATION_JSON));
        BlizzardAppTokenService tokens = service("id", "secret");

        assertThat(tokens.token()).isEqualTo("test-token");
        clock.now = clock.now.plus(Duration.ofHours(22));
        assertThat(tokens.token()).isEqualTo("test-token");
        clock.now = clock.now.plus(Duration.ofHours(1).plusMinutes(1));
        assertThat(tokens.token()).isEqualTo("test-token");

        server.verify();
    }

    @Test
    void tokenRequestIsABasicClientCredentialsPost() throws IOException {
        String basic = "Basic " + Base64.getEncoder().encodeToString("id:secret".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(URL)).andExpect(header("Authorization", basic))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess(fixture("token.json"), MediaType.APPLICATION_JSON));

        service("id", "secret").token();

        server.verify();
    }

    @Test
    void tokenRequestCountsInTheLimiter() throws IOException {
        server.expect(requestTo(URL)).andRespond(withSuccess(fixture("token.json"), MediaType.APPLICATION_JSON));

        service("id", "secret").token();

        assertThat(acquired).hasValue(1);
    }

    @Test
    void refusedByTheLimiterIsUnavailable() {
        when(limiter.budget()).thenReturn((FetchBudget) () -> false);

        assertThatThrownBy(() -> service("id", "secret").token()).isInstanceOf(BlizzardUnavailableException.class);
        server.verify();
    }

    @Test
    void unavailableWithoutCredentials() {
        BlizzardAppTokenService tokens = service("", " ");

        assertThat(tokens.available()).isFalse();
        assertThat(service("id", "secret").available()).isTrue();
        assertThatThrownBy(tokens::token).isInstanceOf(BlizzardUnavailableException.class);
        assertThat(acquired).hasValue(0);
    }

    @Test
    void tokenNeverInExceptionMessage() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"access_token\":\"SECRET\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service("id", "secret").token()).isInstanceOf(BlizzardUnavailableException.class)
                .message().doesNotContain("SECRET");
    }

    @Test
    void garbledBodyIsUnavailableWithoutTheBody() {
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>SECRET</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> service("id", "secret").token()).isInstanceOf(BlizzardUnavailableException.class)
                .message().doesNotContain("SECRET");
    }

    @Test
    void rejectedCredentialsAreUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("{\"error\":\"invalid_client\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service("id", "secret").token()).isInstanceOf(BlizzardUnavailableException.class)
                .message().doesNotContain("invalid_client");
    }

    @Test
    void invalidateForcesANewToken() throws IOException {
        server.expect(times(2), requestTo(URL))
                .andRespond(withSuccess(fixture("token.json"), MediaType.APPLICATION_JSON));
        BlizzardAppTokenService tokens = service("id", "secret");

        tokens.token();
        tokens.invalidate("another-token");
        tokens.token();
        tokens.invalidate("test-token");
        tokens.token();

        server.verify();
    }

    @Test
    void concurrentCallersShareOneRenewal() throws Exception {
        server.expect(requestTo(URL)).andRespond(withSuccess(fixture("token.json"), MediaType.APPLICATION_JSON));
        BlizzardAppTokenService tokens = service("id", "secret");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(tokens::token));
            }
            for (java.util.concurrent.Future<String> result : results) {
                assertThat(result.get()).isEqualTo("test-token");
            }
        } finally {
            pool.shutdownNow();
        }
        verify(limiter, org.mockito.Mockito.atLeast(1)).budget();
        assertThat(acquired).hasValue(1);
    }
}
