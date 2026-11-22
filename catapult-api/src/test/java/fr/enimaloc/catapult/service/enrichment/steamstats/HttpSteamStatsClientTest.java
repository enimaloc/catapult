package fr.enimaloc.catapult.service.enrichment.steamstats;

import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import fr.enimaloc.catapult.getter.steam.SteamRateLimiter;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpSteamStatsClientTest {

    private static final String API = "https://api.steampowered.com";
    private static final String STATS = API + "/ISteamUserStats";

    private MockRestServiceServer server;
    private SteamApiKeyRotator rotator;
    private SteamRateLimiter limiter;
    private HttpSteamStatsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        rotator = mock(SteamApiKeyRotator.class);
        limiter = mock(SteamRateLimiter.class);
        when(rotator.nextKey()).thenReturn(Optional.of("poolkey"));
        when(limiter.tryAcquireNow(anyString())).thenReturn(true);
        client = new HttpSteamStatsClient(builder.build(), Optional.of(rotator), Optional.of(limiter));
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("enrichment/steamstats/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void parsesGlobalPercentagesFromStrings() throws IOException {
        server.expect(once(), requestTo(STATS + "/GetGlobalAchievementPercentagesForApp/v0002/?gameid=440"
                        + "&format=json&key=poolkey"))
                .andRespond(withSuccess(fixture("global-percentages-440.json"), MediaType.APPLICATION_JSON));

        var percentages = client.globalPercentages("440");

        assertThat(percentages).hasSize(5);
        assertThat(percentages.get("TF_SCOUT_LONG_DISTANCE_RUNNER")).isEqualByComparingTo(new BigDecimal("49.4"));
        server.verify();
    }

    @Test
    void readsTheLibrary() throws IOException {
        server.expect(once(), requestTo(API + "/IPlayerService/GetOwnedGames/v1/?key=poolkey&steamid=7656"
                        + "&include_appinfo=0&include_played_free_games=1"))
                .andRespond(withSuccess(fixture("owned-games.json"), MediaType.APPLICATION_JSON));

        SteamLibrary library = client.library("7656", null).orElseThrow();

        assertThat(library.games()).containsOnlyKeys("440", "570", "730");
        assertThat(library.games().get("440")).isEqualTo(
                new SteamLibrary.Entry(7320, 95, Instant.ofEpochSecond(1763756040L)));
        assertThat(library.games().get("730").playtimeTwoWeeksMinutes()).isZero();
        assertThat(library.games().get("730").lastPlayed()).isNull();
    }

    @Test
    void privateLibraryIsEmpty() throws IOException {
        server.expect(once(), requestTo(API + "/IPlayerService/GetOwnedGames/v1/?key=poolkey&steamid=7656"
                        + "&include_appinfo=0&include_played_free_games=1"))
                .andRespond(withSuccess(fixture("owned-games-private.json"), MediaType.APPLICATION_JSON));

        assertThat(client.library("7656", null)).isEmpty();
    }

    @Test
    void readsPlayerAchievements() throws IOException {
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=poolkey&steamid=7656&appid=440"
                        + "&l=french"))
                .andRespond(withSuccess(fixture("player-achievements.json"), MediaType.APPLICATION_JSON));

        SteamPlayerAchievements result = client.playerAchievements("7656", "440", "french", null);

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.unlocked()).containsOnlyKeys("TF_SCOUT_LONG_DISTANCE_RUNNER", "TF_HEAVY_DAMAGE_TAKEN",
                "TF_GET_CONSECUTIVEKILLS_NODEATHS");
        assertThat(result.unlocked().get("TF_HEAVY_DAMAGE_TAKEN")).isEqualTo(Instant.ofEpochSecond(1763500000L));
    }

    @Test
    void privateAchievementsArePrivate() throws IOException {
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=poolkey&steamid=7656&appid=440"
                        + "&l=english"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("player-achievements-private.json")));

        assertThat(client.playerAchievements("7656", "440", "english", null).status()).isEqualTo(Status.PRIVATE);
    }

    @Test
    void privateAchievementsAnsweredWith200AreAlsoPrivate() throws IOException {
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=poolkey&steamid=7656&appid=440"
                        + "&l=english"))
                .andRespond(withSuccess(fixture("player-achievements-private.json"), MediaType.APPLICATION_JSON));

        assertThat(client.playerAchievements("7656", "440", "english", null).status()).isEqualTo(Status.PRIVATE);
    }

    @Test
    void appWithoutStatsHasNoAchievements() throws IOException {
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=poolkey&steamid=7656&appid=1"
                        + "&l=english"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("player-achievements-nostats.json")));

        assertThat(client.playerAchievements("7656", "1", "english", null).status())
                .isEqualTo(Status.NO_ACHIEVEMENTS);
    }

    @Test
    void anotherClientErrorIsAnError() {
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=poolkey&steamid=7656&appid=1"
                        + "&l=english"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_HTML)
                        .body("<html>Bad request</html>"));

        assertThatThrownBy(() -> client.playerAchievements("7656", "1", "english", null))
                .isInstanceOf(SteamStatsUnavailableException.class);
    }

    @Test
    void readsTheSchema() throws IOException {
        server.expect(once(), requestTo(STATS + "/GetSchemaForGame/v2/?key=poolkey&appid=440&l=english"))
                .andRespond(withSuccess(fixture("schema.json"), MediaType.APPLICATION_JSON));

        SteamAchievementSchema schema = client.schema("440", "english").orElseThrow();

        assertThat(schema.achievements()).hasSize(5);
        assertThat(schema.achievements().get(2)).isEqualTo(new SteamAchievementSchema.Definition(
                "TF_GET_CONSECUTIVEKILLS_NODEATHS", "Streak", "Kill streak", "https://cdn.example/icon3.jpg",
                "https://cdn.example/gray3.jpg", true));
    }

    @Test
    void appWithoutSchemaIsAbsent() {
        server.expect(once(), requestTo(STATS + "/GetSchemaForGame/v2/?key=poolkey&appid=1&l=english"))
                .andRespond(withSuccess("{\"game\":{}}", MediaType.APPLICATION_JSON));

        assertThat(client.schema("1", "english")).isEmpty();
    }

    @Test
    void redirectWithKeyIsNotFollowed() {
        server.expect(once(), requestTo(STATS + "/GetSchemaForGame/v2/?key=poolkey&appid=440&l=english"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://evil.example/x"));

        assertThatThrownBy(() -> client.schema("440", "english"))
                .isInstanceOf(SteamStatsUnavailableException.class);
        server.verify();
    }

    @Test
    void garbled200IsAnError() {
        server.expect(once(), requestTo(STATS + "/GetSchemaForGame/v2/?key=poolkey&appid=440&l=english"))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.schema("440", "english"))
                .isInstanceOf(SteamStatsUnavailableException.class);
    }

    @Test
    void wellFormedButWrongShapedAnswersAreErrors() {
        String schemaUrl = STATS + "/GetSchemaForGame/v2/?key=poolkey&appid=1&l=english";
        server.expect(once(), requestTo(schemaUrl)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=poolkey&steamid=7656&appid=1"
                        + "&l=english")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(API + "/IPlayerService/GetOwnedGames/v1/?key=poolkey&steamid=7656"
                        + "&include_appinfo=0&include_played_free_games=1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.schema("1", "english")).isInstanceOf(SteamStatsUnavailableException.class);
        assertThatThrownBy(() -> client.playerAchievements("7656", "1", "english", null))
                .isInstanceOf(SteamStatsUnavailableException.class);
        assertThatThrownBy(() -> client.library("7656", null)).isInstanceOf(SteamStatsUnavailableException.class);
    }

    @Test
    void serverErrorIsAnError() {
        server.expect(once(), requestTo(STATS + "/GetSchemaForGame/v2/?key=poolkey&appid=440&l=english"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.schema("440", "english"))
                .isInstanceOf(SteamStatsUnavailableException.class);
    }

    @Test
    void everyRequestAsksTheRateLimiter() throws IOException {
        server.expect(once(), requestTo(STATS + "/GetSchemaForGame/v2/?key=poolkey&appid=440&l=english"))
                .andRespond(withSuccess(fixture("schema.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(STATS + "/GetGlobalAchievementPercentagesForApp/v0002/?gameid=440"
                        + "&format=json&key=poolkey"))
                .andRespond(withSuccess(fixture("global-percentages-440.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=poolkey&steamid=7656&appid=440"
                        + "&l=english"))
                .andRespond(withSuccess(fixture("player-achievements.json"), MediaType.APPLICATION_JSON));

        client.schema("440", "english");
        client.globalPercentages("440");
        client.playerAchievements("7656", "440", "english", null);

        verify(limiter, times(3)).tryAcquireNow("poolkey");
    }

    @Test
    void refusedByTheRateLimiterMakesNoRequest() {
        when(limiter.tryAcquireNow("poolkey")).thenReturn(false);

        assertThatThrownBy(() -> client.schema("440", "english"))
                .isInstanceOf(SteamStatsUnavailableException.class);
        server.verify();
    }

    @Test
    void noKeyAtAllIsUnavailable() {
        when(rotator.nextKey()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> client.globalPercentages("440")).isInstanceOf(SteamStatsUnavailableException.class);
    }

    @Test
    void usableOnlyWithSteamOnAndAKey() {
        when(rotator.hasKeys()).thenReturn(true);
        assertThat(client.usable(null)).isTrue();
        when(rotator.hasKeys()).thenReturn(false);
        assertThat(client.usable(null)).as("no pool key, no personal key").isFalse();
        assertThat(client.usable(" ")).isFalse();
        assertThat(client.usable("mykey")).isTrue();

        HttpSteamStatsClient off = new HttpSteamStatsClient(RestClient.builder().build(), Optional.empty(),
                Optional.empty());
        assertThat(off.usable("mykey")).as("steam disabled").isFalse();
        assertThat(off.usable(null)).isFalse();
    }

    @Test
    void aRefusedPersonalKeyFallsBackToThePoolOnce() throws IOException {
        server.expect(once(), requestTo(API + "/IPlayerService/GetOwnedGames/v1/?key=badkey&steamid=7656"
                        + "&include_appinfo=0&include_played_free_games=1"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("<html>Access is denied</html>"));
        server.expect(once(), requestTo(API + "/IPlayerService/GetOwnedGames/v1/?key=poolkey&steamid=7656"
                        + "&include_appinfo=0&include_played_free_games=1"))
                .andRespond(withSuccess(fixture("owned-games.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=badkey&steamid=7656&appid=440"
                        + "&l=english"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=poolkey&steamid=7656&appid=440"
                        + "&l=english"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.library("7656", "badkey")).isPresent();
        assertThatThrownBy(() -> client.playerAchievements("7656", "440", "english", "badkey"))
                .as("the pool is asked once, not again")
                .isInstanceOf(SteamStatsUnavailableException.class)
                .hasMessageNotContaining("badkey").hasMessageNotContaining("poolkey");
        server.verify();
    }

    @Test
    void aPrivateProfile403KeepsThePersonalKey() throws IOException {
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=mykey&steamid=7656&appid=440"
                        + "&l=english"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"playerstats\":{\"error\":\"Profile is not public\",\"success\":false}}"));

        assertThat(client.playerAchievements("7656", "440", "english", "mykey").status())
                .isEqualTo(Status.PRIVATE);
        verify(rotator, never()).nextKey();
        server.verify();
    }

    @Test
    void personalKeyWinsOverPool() throws IOException {
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=mykey&steamid=7656&appid=440"
                        + "&l=english"))
                .andExpect(header(HttpHeaders.USER_AGENT, org.hamcrest.Matchers.startsWith("Catapult/")))
                .andRespond(withSuccess(fixture("player-achievements.json"), MediaType.APPLICATION_JSON));

        client.playerAchievements("7656", "440", "english", "mykey");

        verify(rotator, never()).nextKey();
        verify(limiter).tryAcquireNow("mykey");
    }

    @Test
    void rateLimitedPoolKeyIsThrottledInTheRotator() {
        server.expect(once(), requestTo(STATS + "/GetSchemaForGame/v2/?key=poolkey&appid=440&l=english"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "30"));

        assertThatThrownBy(() -> client.schema("440", "english")).isInstanceOf(SteamStatsUnavailableException.class);

        verify(rotator).onKeyRateLimited("poolkey", 30);
        verify(limiter, never()).onRateLimitResponse(anyString(), anyInt());
    }

    @Test
    void rateLimitedPersonalKeyIsThrottledInTheLimiter() {
        server.expect(once(), requestTo(STATS + "/GetPlayerAchievements/v1/?key=mykey&steamid=7656&appid=440"
                        + "&l=english"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.playerAchievements("7656", "440", "english", "mykey"))
                .isInstanceOf(SteamStatsUnavailableException.class);

        verify(limiter).onRateLimitResponse(eq("mykey"), anyInt());
        verify(rotator, never()).onKeyRateLimited(anyString(), anyInt());
    }

    @Test
    void transportNeverFollowsARedirectItself() throws IOException {
        assertFollowsNoRedirect(HttpSteamStatsClient.requestFactory());
    }

    @Test
    void theInjectedConstructorUsesTheNonRedirectingTransport() throws IOException {
        RestClient.Builder builder = spy(RestClient.builder());
        ArgumentCaptor<ClientHttpRequestFactory> factory = ArgumentCaptor.forClass(ClientHttpRequestFactory.class);

        new HttpSteamStatsClient(builder, Optional.of(rotator), Optional.of(limiter));

        verify(builder).requestFactory(factory.capture());
        assertFollowsNoRedirect(factory.getValue());
    }

    private static void assertFollowsNoRedirect(ClientHttpRequestFactory factory) throws IOException {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer http =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/start", ex -> {
            ex.getResponseHeaders().add("Location", "/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        http.createContext("/elsewhere", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        http.start();
        try {
            int status = RestClient.builder().requestFactory(factory).build().get()
                    .uri("http://127.0.0.1:" + http.getAddress().getPort() + "/start")
                    .exchange((req, res) -> res.getStatusCode().value());

            assertThat(status).isEqualTo(302);
            assertThat(hits).hasValue(0);
        } finally {
            http.stop(0);
        }
    }
}
