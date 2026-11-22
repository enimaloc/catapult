package fr.enimaloc.catapult.service.enrichment.speedrun;

import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpSpeedrunClientTest {

    private static final String BASE = "https://www.speedrun.com/api/v1";

    private MockRestServiceServer server;
    private SpeedrunRateLimiter limiter;
    private HttpSpeedrunClient client;
    private final CountingBudget memberExtra = new CountingBudget(Integer.MAX_VALUE);

    /** A budget that counts what it is asked and grants while under {@code limit}. */
    private static final class CountingBudget extends java.util.concurrent.atomic.AtomicInteger implements FetchBudget {
        private final int limit;

        CountingBudget(int limit) {
            this.limit = limit;
        }

        @Override
        public boolean tryAcquire() {
            return incrementAndGet() <= limit;
        }
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        limiter = mock(SpeedrunRateLimiter.class);
        when(limiter.member()).thenReturn(memberExtra);
        client = new HttpSpeedrunClient(builder.build(), limiter);
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("enrichment/speedrun/" + name + ".json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void identifiesCatapult() throws IOException {
        server.expect(once(), requestTo(BASE + "/games?name=Celeste&max=20"))
                .andExpect(header(HttpHeaders.USER_AGENT, "Catapult/1.0 (+https://catapult.enimaloc.fr)"))
                .andRespond(withSuccess(fixture("games-celeste"), MediaType.APPLICATION_JSON));

        client.searchGames("Celeste");

        server.verify();
    }

    @Test
    void searchGames_readsNamesAndCover() throws IOException {
        server.expect(once(), requestTo(BASE + "/games?name=Celeste&max=20"))
                .andRespond(withSuccess(fixture("games-celeste"), MediaType.APPLICATION_JSON));

        List<SpeedrunGame> games = client.searchGames("Celeste");

        assertThat(games).extracting(SpeedrunGame::name).containsExactly("Celeste", "Celeste 64", "CelesteNES");
        SpeedrunGame celeste = games.getFirst();
        assertThat(celeste.id()).isEqualTo("o1y9j9v6");
        assertThat(celeste.twitchName()).isEqualTo("Celeste");
        assertThat(celeste.abbreviation()).isEqualTo("celeste");
        assertThat(celeste.url()).isEqualTo("https://www.speedrun.com/celeste");
        assertThat(celeste.coverUrl()).startsWith("https://www.speedrun.com/static/game/o1y9j9v6/cover");
        server.verify();
    }

    @Test
    void parsesRecordsInSiteOrderWithTies() throws IOException {
        server.expect(once(), requestTo(BASE + "/games/o1y9j9v6/records?scope=full-game&miscellaneous=no&top=1"
                        + "&skip-empty=true&embed=game,category,players&max=20"))
                .andRespond(withSuccess(fixture("records-celeste"), MediaType.APPLICATION_JSON));

        SpeedrunRecords records = client.records("o1y9j9v6").orElseThrow();

        assertThat(records.game().name()).isEqualTo("Celeste");
        assertThat(records.categories()).extracting(SpeedrunRecords.CategoryRecord::category)
                .containsExactly("Any%", "All Red Berries");
        SpeedrunRecords.CategoryRecord any = records.categories().getFirst();
        assertThat(any.categoryId()).isEqualTo("7kjpl1gk");
        assertThat(any.url()).isEqualTo("https://www.speedrun.com/celeste?h=any&x=7kjpl1gk");
        assertThat(any.time()).isEqualByComparingTo(new BigDecimal("1484.083"));
        assertThat(any.timing()).isEqualTo("ingame");
        assertThat(any.runners()).containsExactly(
                new SpeedrunRecords.Runner("secureaccount", "https://www.speedrun.com/users/secureaccount"));
        assertThat(any.date()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(any.runUrl()).isEqualTo("https://www.speedrun.com/celeste/runs/z54n5v5m");
        assertThat(any.videoUrl()).isEqualTo("https://youtu.be/pUexvH_hhgM");
        SpeedrunRecords.CategoryRecord tie = records.categories().get(1);
        assertThat(tie.time()).isEqualByComparingTo(new BigDecimal("2682.345"));
        assertThat(tie.runners()).hasSize(2);
        assertThat(tie.videoUrl()).as("two URLs pasted in one link are skipped")
                .isEqualTo("https://www.youtube.com/watch?v=CMNkc2jtet0");
        assertThat(tie.runners().getFirst().url()).isNotNull();
        assertThat(tie.runners().get(1)).isEqualTo(new SpeedrunRecords.Runner("Madeline", null));
    }

    @Test
    void recordsOfAGameWithoutAnyRunStillNameTheGame() throws IOException {
        server.expect(once(), requestTo(BASE + "/games/o1y9j9v6/records?scope=full-game&miscellaneous=no&top=1"
                        + "&skip-empty=true&embed=game,category,players&max=20"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/games/o1y9j9v6"))
                .andRespond(withSuccess("{\"data\":{\"id\":\"o1y9j9v6\",\"names\":{\"international\":\"Celeste\"}}}",
                        MediaType.APPLICATION_JSON));

        CountingBudget extra = new CountingBudget(Integer.MAX_VALUE);
        SpeedrunRecords records = client.charging(extra).records("o1y9j9v6").orElseThrow();

        assertThat(records.game().id()).isEqualTo("o1y9j9v6");
        assertThat(records.categories()).isEmpty();
        assertThat(extra).as("the game lookup is a second request, asked of the budget").hasValue(1);
        assertThat(memberExtra).hasValue(0);
    }

    @Test
    void recordsFallbackRefusedByTheBudgetSendsNoSecondRequest() {
        server.expect(once(), requestTo(BASE + "/games/o1y9j9v6/records?scope=full-game&miscellaneous=no&top=1"
                        + "&skip-empty=true&embed=game,category,players&max=20"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.charging(new CountingBudget(0)).records("o1y9j9v6"))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        server.verify();
    }

    @Test
    void recordsWithRunsAskNoExtraRequest() throws IOException {
        server.expect(once(), requestTo(BASE + "/games/o1y9j9v6/records?scope=full-game&miscellaneous=no&top=1"
                        + "&skip-empty=true&embed=game,category,players&max=20"))
                .andRespond(withSuccess(fixture("records-celeste"), MediaType.APPLICATION_JSON));
        CountingBudget extra = new CountingBudget(Integer.MAX_VALUE);

        assertThat(client.charging(extra).records("o1y9j9v6")).isPresent();
        assertThat(extra).hasValue(0);
    }

    @Test
    void unknownGameHasNoRecords() {
        server.expect(once(), requestTo(BASE + "/games/nope/records?scope=full-game&miscellaneous=no&top=1"
                        + "&skip-empty=true&embed=game,category,players&max=20"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.records("nope")).isEmpty();
    }

    @Test
    void usersByTwitch_readsTheTwitchLink() throws IOException {
        server.expect(once(), requestTo(BASE + "/users?twitch=linkus7&max=20"))
                .andRespond(withSuccess(fixture("users-twitch"), MediaType.APPLICATION_JSON));

        assertThat(client.usersByTwitch("linkus7")).containsExactly(new SpeedrunUser("qj26goxk", "Linkus7",
                "https://www.speedrun.com/users/Linkus7", "https://www.twitch.tv/linkus7"));
    }

    @Test
    void user_followsTheRedirectToTheId() throws IOException {
        server.expect(once(), requestTo(BASE + "/users/linkus7"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/api/v1/users/qj26goxk"));
        server.expect(once(), requestTo(BASE + "/users/qj26goxk"))
                .andRespond(withSuccess(fixture("user"), MediaType.APPLICATION_JSON));

        assertThat(client.user("linkus7")).hasValueSatisfying(u -> assertThat(u.id()).isEqualTo("qj26goxk"));
        server.verify();
        assertThat(memberExtra).as("each redirect hop is a request of the member share by default").hasValue(1);
    }

    @Test
    void user_redirectHopRefusedByTheBudgetIsNotFollowed() {
        server.expect(once(), requestTo(BASE + "/users/linkus7"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/api/v1/users/qj26goxk"));

        assertThatThrownBy(() -> client.charging(new CountingBudget(0)).user("linkus7"))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        server.verify();
    }

    @Test
    void user_neverFollowsARedirectOutOfTheApi() {
        server.expect(once(), requestTo(BASE + "/users/x"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://evil.example/x"));

        assertThatThrownBy(() -> client.user("x")).isInstanceOf(SpeedrunUnavailableException.class);
    }

    @Test
    void unknownUserIsAbsent() {
        server.expect(once(), requestTo(BASE + "/users/ghost")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.user("ghost")).isEmpty();
    }

    @Test
    void keyOnlyInHeader() throws IOException {
        server.expect(once(), request -> assertThat(request.getURI().toString()).doesNotContain("secretkey"))
                .andExpect(requestTo(BASE + "/profile"))
                .andExpect(header("X-API-Key", "secretkey"))
                .andRespond(withSuccess(fixture("profile"), MediaType.APPLICATION_JSON));

        assertThat(client.profile("secretkey")).hasValueSatisfying(u -> assertThat(u.name()).isEqualTo("Linkus7"));
        server.verify();
    }

    @Test
    void profile403IsEmpty() {
        server.expect(once(), requestTo(BASE + "/profile")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(client.profile("bad")).isEmpty();
        verify(limiter, never()).pause(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void status420PausesTheLimiter() {
        server.expect(once(), requestTo(BASE + "/users/ghost")).andRespond(withStatus(HttpStatusCode.valueOf(420)));

        assertThatThrownBy(() -> client.user("ghost")).isInstanceOf(SpeedrunUnavailableException.class);
        verify(limiter).pause(Duration.ofSeconds(60));
    }

    @Test
    void status429PausesTheLimiterToo() {
        server.expect(once(), requestTo(BASE + "/users/ghost")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.user("ghost")).isInstanceOf(SpeedrunUnavailableException.class);
        verify(limiter).pause(Duration.ofSeconds(60));
    }

    @Test
    void serverErrorThrowsWithoutPausing() {
        server.expect(once(), requestTo(BASE + "/users/ghost")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.user("ghost")).isInstanceOf(SpeedrunUnavailableException.class);
        verify(limiter, never()).pause(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pausedLimiterSendsNothing() {
        when(limiter.paused()).thenReturn(true);

        assertThatThrownBy(() -> client.searchGames("Celeste")).isInstanceOf(SpeedrunUnavailableException.class);
        server.verify();
    }

    @Test
    void levelRunsAreDropped() throws IOException {
        server.expect(once(), requestTo(BASE + "/users/qj26goxk/personal-bests?embed=game,category&game=gamex"))
                .andRespond(withSuccess(fixture("personal-bests"), MediaType.APPLICATION_JSON));

        List<SpeedrunRun> runs = client.personalBests("qj26goxk", "gamex");

        assertThat(runs).hasSize(3).extracting(SpeedrunRun::runUrl).doesNotContain(
                "https://www.speedrun.com/lm/runs/ilrun001");
        SpeedrunRun first = runs.getFirst();
        assertThat(first.place()).isEqualTo(28);
        assertThat(first.game()).isEqualTo("Luigi's Mansion");
        assertThat(first.gameId()).isEqualTo("o1y9lk6q");
        assertThat(first.category()).isEqualTo("Any% (No OoB) [PAL]");
        assertThat(first.categoryId()).isEqualTo("z27g0og2");
        assertThat(first.time()).isEqualByComparingTo(new BigDecimal("4974"));
        assertThat(first.date()).isEqualTo(LocalDate.of(2013, 9, 16));
        assertThat(first.submitted()).isEqualTo(Instant.parse("2014-12-02T22:13:01Z"));
    }

    @Test
    void personalBestsOfAllGamesAskNoGameFilter() throws IOException {
        server.expect(once(), requestTo(BASE + "/users/qj26goxk/personal-bests?embed=game,category"))
                .andRespond(withSuccess(fixture("personal-bests"), MediaType.APPLICATION_JSON));

        assertThat(client.personalBests("qj26goxk", null)).hasSize(3);
    }

    @Test
    void pendingRunsHaveNoPlaceAndSendTheKeyWhenGiven() throws IOException {
        server.expect(once(), requestTo(BASE + "/runs?user=qj26goxk&game=m9dok1pr&status=new"
                        + "&embed=game,category&max=20"))
                .andExpect(header("X-API-Key", "k1"))
                .andRespond(withSuccess(fixture("runs-new"), MediaType.APPLICATION_JSON));

        List<SpeedrunRun> runs = client.pendingRuns("qj26goxk", "m9dok1pr", "k1");

        assertThat(runs).hasSize(2);
        assertThat(runs.getFirst().place()).isNull();
        assertThat(runs.getFirst().game()).isEqualTo("Mario Kart 64");
        assertThat(runs.getFirst().runUrl()).isEqualTo("https://www.speedrun.com/mk64/runs/m7k97dez");
        assertThat(runs.getFirst().submitted()).isNotNull();
    }

    @Test
    void pendingRunsOfAllGamesAskFiftyWithoutKey() throws IOException {
        server.expect(once(), requestTo(BASE + "/runs?user=qj26goxk&status=new&embed=game,category&max=50"))
                .andExpect(headerDoesNotExist("X-API-Key"))
                .andRespond(withSuccess(fixture("runs-new"), MediaType.APPLICATION_JSON));

        assertThat(client.pendingRuns("qj26goxk", null, null)).hasSize(2);
    }

    @Test
    void blankBodyIsAnOutageNotAnAbsence() {
        server.expect(once(), requestTo(BASE + "/games?name=Celeste&max=20"))
                .andRespond(withSuccess("  ", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.searchGames("Celeste")).isInstanceOf(SpeedrunUnavailableException.class);
    }

    @Test
    void htmlBodyIsAnOutageNotAnAbsence() {
        server.expect(once(), requestTo(BASE + "/users/x"))
                .andRespond(withSuccess("<html>Bad gateway</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.user("x")).isInstanceOf(SpeedrunUnavailableException.class);
    }

    @Test
    void user_rejectsADotDotRedirectOutOfTheApi() {
        server.expect(once(), requestTo(BASE + "/users/x"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/api/v1/../evil"));

        assertThatThrownBy(() -> client.user("x")).isInstanceOf(SpeedrunUnavailableException.class);
    }

    @Test
    void transportNeverFollowsARedirectItself() throws IOException {
        assertFollowsNoRedirect(HttpSpeedrunClient.requestFactory());
    }

    @Test
    void theInjectedConstructorUsesTheNonRedirectingTransport() throws IOException {
        RestClient.Builder builder = spy(RestClient.builder());
        ArgumentCaptor<ClientHttpRequestFactory> factory = ArgumentCaptor.forClass(ClientHttpRequestFactory.class);

        new HttpSpeedrunClient(builder, limiter);

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
