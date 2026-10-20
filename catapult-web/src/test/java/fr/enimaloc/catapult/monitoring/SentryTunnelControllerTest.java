package fr.enimaloc.catapult.monitoring;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The browser's reports go on to Sentry, but only those for catapult-web's own project. */
class SentryTunnelControllerTest {
    private MockWebServer sentry;
    private String dsn;
    private SentryTunnelController tunnel;

    @BeforeEach
    void setUp() throws IOException {
        sentry = new MockWebServer();
        sentry.start();
        dsn = "http://key@" + sentry.getHostName() + ":" + sentry.getPort() + "/42";
        tunnel = new SentryTunnelController(dsn, HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() throws IOException {
        sentry.shutdown();
    }

    private static byte[] envelope(String dsn) {
        return ("{\"dsn\":\"" + dsn + "\",\"sent_at\":\"2026-10-14T16:00:00Z\"}\n{\"type\":\"event\"}\n{}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anEnvelopeForItsOwnProject_goesOnToTheProjectsEnvelopeEndpoint() throws Exception {
        sentry.enqueue(new MockResponse().setResponseCode(200));
        byte[] envelope = envelope(dsn);

        assertThat(tunnel.relay(envelope).getStatusCode().value()).isEqualTo(200);

        RecordedRequest relayed = sentry.takeRequest(1, TimeUnit.SECONDS);
        assertThat(relayed.getPath()).isEqualTo("/api/42/envelope/");
        assertThat(relayed.getHeader("Content-Type")).isEqualTo("application/x-sentry-envelope");
        assertThat(relayed.getBody().readByteArray()).isEqualTo(envelope);
    }

    @Test
    void sentrysAnswer_isPassedBack() throws Exception {
        sentry.enqueue(new MockResponse().setResponseCode(429));

        assertThat(tunnel.relay(envelope(dsn)).getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void anEnvelopeForAnotherProjectOrHost_isRefused_andGoesNowhere() throws Exception {
        assertThat(tunnel.relay(envelope(dsn.replace("/42", "/7"))).getStatusCode().value()).isEqualTo(400);
        assertThat(tunnel.relay(envelope("https://key@o1.ingest.sentry.io/42")).getStatusCode().value()).isEqualTo(400);
        assertThat(tunnel.relay("not an envelope".getBytes(StandardCharsets.UTF_8)).getStatusCode().value()).isEqualTo(400);

        assertThat(sentry.getRequestCount()).isZero();
    }

    @Test
    void anOversizedEnvelope_isRefused() throws Exception {
        byte[] envelope = new byte[SentryTunnelController.MAX_ENVELOPE_BYTES + 1];

        assertThat(tunnel.relay(envelope).getStatusCode().value()).isEqualTo(413);
        assertThat(sentry.getRequestCount()).isZero();
    }

    @Test
    void anOversizedRequest_isRefusedFromItsLength_unread() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", SentryTunnelController.PATH);
        request.setContent(new byte[SentryTunnelController.MAX_ENVELOPE_BYTES + 1]);

        assertThat(tunnel.relay(request).getStatusCode().value()).isEqualTo(413);
        assertThat(request.getInputStream().available()).isEqualTo(SentryTunnelController.MAX_ENVELOPE_BYTES + 1);
    }

    @Test
    void aRequestOfUntoldLength_isReadNoFurtherThanTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", SentryTunnelController.PATH) {
            @Override
            public long getContentLengthLong() {
                return -1; // chunked
            }
        };
        request.setContent(new byte[SentryTunnelController.MAX_ENVELOPE_BYTES + 10]);

        assertThat(tunnel.relay(request).getStatusCode().value()).isEqualTo(413);
        assertThat(request.getInputStream().available()).isEqualTo(9);
    }

    @Test
    void aRequest_isRelayedAsItsEnvelope() throws Exception {
        sentry.enqueue(new MockResponse().setResponseCode(200));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", SentryTunnelController.PATH);
        request.setContent(envelope(dsn));

        assertThat(tunnel.relay(request).getStatusCode().value()).isEqualTo(200);
        assertThat(sentry.takeRequest(1, TimeUnit.SECONDS).getBody().readByteArray()).isEqualTo(envelope(dsn));
    }

    @Test
    void tooManyAtOnce_areToldToBackOff() throws Exception {
        sentry.enqueue(new MockResponse().setResponseCode(200).setHeadersDelay(2, TimeUnit.SECONDS));
        for (int i = 1; i < SentryTunnelController.MAX_IN_FLIGHT; i++) {
            sentry.enqueue(new MockResponse().setResponseCode(200).setHeadersDelay(2, TimeUnit.SECONDS));
        }
        ExecutorService pending = Executors.newFixedThreadPool(SentryTunnelController.MAX_IN_FLIGHT);
        try {
            for (int i = 0; i < SentryTunnelController.MAX_IN_FLIGHT; i++) {
                pending.submit(() -> tunnel.relay(envelope(dsn)));
            }
            while (sentry.getRequestCount() < SentryTunnelController.MAX_IN_FLIGHT) Thread.sleep(10);

            assertThat(tunnel.relay(envelope(dsn)).getStatusCode().value()).isEqualTo(429);
            // Refused before its body is read: a body held in memory counts as much as a wait.
            org.springframework.mock.web.MockHttpServletRequest unread = new org.springframework.mock.web.MockHttpServletRequest("POST", SentryTunnelController.PATH) {
                @Override
                public jakarta.servlet.ServletInputStream getInputStream() {
                    throw new AssertionError("read while every slot is taken");
                }
            };
            assertThat(tunnel.relay(unread).getStatusCode().value()).isEqualTo(429);
        } finally {
            pending.shutdownNow();
        }
    }

    @Test
    void withoutADsn_thereIsNoTunnel() throws Exception {
        assertThat(new SentryTunnelController("").relay(envelope(dsn)).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void anUnreachableSentry_isABadGateway() throws Exception {
        sentry.shutdown();

        assertThat(tunnel.relay(envelope(dsn)).getStatusCode().value()).isEqualTo(502);
    }

    @Test
    void ingest_keepsTheDsnsPathPrefix() {
        assertThat(SentryTunnelController.ingest(URI.create("https://key@sentry.example.org/sentry/3")))
                .isEqualTo(URI.create("https://sentry.example.org/sentry/api/3/envelope/"));
        assertThat(SentryTunnelController.ingest(URI.create("https://key@o1.ingest.sentry.io/42")))
                .isEqualTo(URI.create("https://o1.ingest.sentry.io/api/42/envelope/"));
    }
}
