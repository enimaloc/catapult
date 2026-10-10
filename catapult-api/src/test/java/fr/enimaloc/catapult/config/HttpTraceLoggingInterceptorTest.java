package fr.enimaloc.catapult.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The outbound HTTP trace log: what it prints, what it redacts, and that it never alters the call. */
class HttpTraceLoggingInterceptorTest {

    private final HttpTraceLoggingInterceptor interceptor = new HttpTraceLoggingInterceptor();
    private final Logger logger = (Logger) LoggerFactory.getLogger(HttpTraceLoggingInterceptor.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
    }

    private static MockClientHttpRequest request(String... headers) {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://api.example/x"));
        for (String header : headers) request.getHeaders().add(header, "secret");
        return request;
    }

    private static ClientHttpRequestExecution respondWith(String body) {
        return (request, payload) -> new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
    }

    private String messages() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void logsRequestAndResponse_neverTheCredentials() throws IOException {
        ClientHttpResponse response = interceptor.intercept(request("Authorization", "Client-Id"),
                "{\"q\":1}".getBytes(StandardCharsets.UTF_8), respondWith("{\"ok\":true}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(messages())
                .contains("HTTP req -> POST https://api.example/x auth=Bearer+ApiKey body={\"q\":1}")
                .contains("HTTP res <- 200 POST https://api.example/x").contains("body={\"ok\":true}")
                .doesNotContain("secret");
    }

    @Test
    void reportsWhichCredentialKindWasSent() throws IOException {
        interceptor.intercept(request("Authorization"), new byte[0], respondWith(""));
        interceptor.intercept(request("X-API-KEY"), null, respondWith(""));
        interceptor.intercept(request(), new byte[0], respondWith(""));

        assertThat(messages()).contains("auth=Bearer body=<empty>").contains("auth=ApiKey").contains("auth=none");
    }

    @Test
    void longBodies_areTruncated() throws IOException {
        String big = "x".repeat(HttpTraceLoggingInterceptor.MAX_BODY_CHARS + 10);

        interceptor.intercept(request(), big.getBytes(StandardCharsets.UTF_8), respondWith("ok"));

        assertThat(messages()).contains("…(+10 chars)");
    }

    @Test
    void unreadableResponses_areReportedNotThrown() throws IOException {
        ClientHttpRequestExecution unreadable = (request, payload) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK) {
            @Override
            public InputStream getBody() throws IOException {
                throw new IOException("stream closed");
            }
        };

        interceptor.intercept(request(), new byte[0], unreadable);

        assertThat(messages()).contains("<unreadable body: stream closed>");
    }

    @Test
    void failedCalls_areLoggedAndRethrown() {
        ClientHttpRequestExecution failing = (request, payload) -> { throw new IOException("connection refused"); };

        assertThatThrownBy(() -> interceptor.intercept(request(), new byte[0], failing)).hasMessage("connection refused");
        assertThat(messages()).contains("HTTP req x  POST https://api.example/x after").contains("connection refused");
    }

    @Test
    void belowTrace_nothingIsLogged() throws IOException {
        logger.setLevel(Level.INFO);

        interceptor.intercept(request(), new byte[0], respondWith("ok"));
        assertThatThrownBy(() -> interceptor.intercept(request(), new byte[0], (r, b) -> { throw new IllegalStateException("x"); }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(logs.list).isEmpty();
    }
}
