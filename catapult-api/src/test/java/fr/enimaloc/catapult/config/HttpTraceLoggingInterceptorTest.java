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
    void credentialQueryParams_areMaskedInTheLoggedUri() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET,
                URI.create("https://api.example/x?key=s1&appid=570&API_KEY=s2&apikey=s3&Token=s4&monkey=ok#f"));

        interceptor.intercept(request, new byte[0], respondWith("{}"));

        assertThat(messages())
                .contains("https://api.example/x?key=***&appid=570&API_KEY=***&apikey=***&Token=***&monkey=ok#f")
                .doesNotContain("s1").doesNotContain("s2").doesNotContain("s3").doesNotContain("s4");
    }

    @Test
    void raKeyParamIsMasked() {
        assertThat(HttpTraceLoggingInterceptor.masked(
                URI.create("https://retroachievements.org/API/API_GetUserProfile.php?u=bob&y=SECRET")))
                .isEqualTo("https://retroachievements.org/API/API_GetUserProfile.php?u=bob&y=***");
        assertThat(HttpTraceLoggingInterceptor.masked(URI.create("https://api.example/x?ay=1&Y=2")))
                .isEqualTo("https://api.example/x?ay=1&Y=***");
    }

    @Test
    void credentialQueryParams_areMaskedWhenTheCallFails() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET,
                URI.create("https://api.example/x?key=s1"));

        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], (r, b) -> {
            throw new IOException("boom");
        })).isInstanceOf(IOException.class);
        assertThat(messages()).contains("https://api.example/x?key=*** after").doesNotContain("s1");
    }

    /** A client with its own, unbuffered request factory (IanaWellKnownRegistry's) still gets the body logged. */
    @Test
    void theCallerStillReadsTheBody_withoutABufferingFactory() throws IOException {
        ClientHttpResponse response = interceptor.intercept(request(), new byte[0], respondWith("{\"ok\":true}"));

        assertThat(messages()).contains("body={\"ok\":true}");
        assertThat(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
    }

    @Test
    void reportsWhichCredentialKindWasSent() throws IOException {
        interceptor.intercept(request("Authorization"), new byte[0], respondWith(""));
        interceptor.intercept(request("X-API-KEY"), null, respondWith(""));
        interceptor.intercept(request(), new byte[0], respondWith(""));

        assertThat(messages()).contains("auth=Bearer body=<empty>").contains("auth=ApiKey").contains("auth=none");
    }

    @Test
    void hypixelApiKeyHeader_isReportedNeverEchoed() throws IOException {
        interceptor.intercept(request("API-Key"), null, respondWith("{\"success\":true}"));

        assertThat(messages()).contains("auth=ApiKey").doesNotContain("secret");
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

    @Test
    void tokenResponseIsMasked() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://oauth.battle.net/token"));

        ClientHttpResponse response = interceptor.intercept(request, "grant_type=client_credentials".getBytes(),
                respondWith("{\"access_token\":\"SECRETTOKEN\",\"token_type\":\"bearer\",\"expires_in\":86399}"));

        assertThat(messages()).doesNotContain("SECRETTOKEN").contains("token_type");
        assertThat(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)).contains("SECRETTOKEN");
    }

    @Test
    void everyProvidersTokenExchangeIsMasked() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://id.twitch.tv/oauth2/token?client_id=cid&client_secret=QSECRET&grant_type=x"));

        interceptor.intercept(request, "client_id=cid&client_secret=BSECRET&refresh_token=RSECRET".getBytes(),
                respondWith("{\"access_token\":\"ATOKEN\",\"refresh_token\":\"RTOKEN\",\"expires_in\":1432}"));

        assertThat(messages())
                .contains("client_id=cid&client_secret=***&grant_type=x")
                .contains("body=client_id=cid&client_secret=***&refresh_token=***")
                .contains("expires_in")
                .doesNotContain("QSECRET").doesNotContain("BSECRET").doesNotContain("RSECRET")
                .doesNotContain("ATOKEN").doesNotContain("RTOKEN");
    }

    @Test
    void bungiesTokenRefreshIsMasked() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://www.bungie.net/platform/app/oauth/token/"));
        request.getHeaders().set("Authorization", "Basic QkFTSUNTRUNSRVQ=");

        interceptor.intercept(request, "grant_type=refresh_token&refresh_token=RSECRET".getBytes(),
                respondWith("{\"access_token\":\"ATOKEN\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
                        + "\"refresh_token\":\"RTOKEN\",\"refresh_expires_in\":7776000,\"membership_id\":\"42\"}"));

        assertThat(messages()).contains("refresh_token=***").contains("membership_id")
                .doesNotContain("RSECRET").doesNotContain("ATOKEN").doesNotContain("RTOKEN")
                .doesNotContain("QkFTSUNTRUNSRVQ=");
    }

    @Test
    void bungiesApiKeyHeaderIsNeverEchoed() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET,
                URI.create("https://www.bungie.net/Platform/Destiny2/3/Profile/1/?components=100"));
        request.getHeaders().set("X-API-Key", "0123456789abcdef0123456789abcdef");
        request.getHeaders().set("Authorization", "Bearer MEMBERTOKEN");

        interceptor.intercept(request, new byte[0], respondWith("{\"ErrorCode\":1}"));

        assertThat(messages()).doesNotContain("0123456789abcdef0123456789abcdef").doesNotContain("MEMBERTOKEN");
    }

    @Test
    void theRiotKeyHeaderIsOnlyReportedAsPresent_andAQuotedKeyIsMasked() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET,
                URI.create("https://euw1.api.riotgames.com/lol/summoner/v4/summoners/by-puuid/p1"));
        request.getHeaders().add("X-Riot-Token", "RGAPI-12345678-abcd-4ef0-9abc-0123456789ab");

        interceptor.intercept(request, new byte[0],
                respondWith("{\"note\":\"echo RGAPI-12345678-ABCD-4ef0-9abc-0123456789ab\"}"));

        assertThat(messages()).contains("auth=ApiKey").doesNotContain("12345678-abcd").doesNotContain("12345678-ABCD")
                .contains("echo ***");
    }

    @Test
    void theFaceitKeyHeader_isOnlyReportedAsPresent() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET,
                URI.create("https://open.faceit.com/data/v4/players?nickname=Catapult"));
        request.getHeaders().add("Authorization", "Bearer 12345678-abcd-4ef0-9abc-0123456789ab");

        interceptor.intercept(request, new byte[0], respondWith("{\"player_id\":\"p\"}"));

        assertThat(messages()).contains("auth=Bearer").contains("nickname=Catapult").doesNotContain("12345678-abcd");
    }

    @Test
    void osusTokenRefreshIsMasked() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://osu.ppy.sh/oauth/token"));

        interceptor.intercept(request, ("client_id=12345&client_secret=CSECRET&grant_type=refresh_token"
                        + "&refresh_token=RSECRET&scope=identify+public").getBytes(),
                respondWith("{\"token_type\":\"Bearer\",\"expires_in\":86400,\"access_token\":\"ATOKEN\","
                        + "\"refresh_token\":\"RTOKEN\"}"));

        assertThat(messages()).contains("refresh_token=***").contains("client_id=12345").contains("expires_in")
                .doesNotContain("CSECRET").doesNotContain("RSECRET").doesNotContain("ATOKEN")
                .doesNotContain("RTOKEN");
    }

    @Test
    void osusMemberTokenIsNeverEchoed() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET,
                URI.create("https://osu.ppy.sh/api/v2/me"));
        request.getHeaders().set("Authorization", "Bearer MEMBERTOKEN");

        interceptor.intercept(request, new byte[0], respondWith("{\"id\":1}"));

        assertThat(messages()).doesNotContain("MEMBERTOKEN");
    }
}
