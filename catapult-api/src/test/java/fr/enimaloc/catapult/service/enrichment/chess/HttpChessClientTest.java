package fr.enimaloc.catapult.service.enrichment.chess;

import fr.enimaloc.catapult.service.enrichment.EnrichmentUserAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpChessClientTest {

    private static final String CHESSCOM = "https://api.chess.com/pub/player/";
    private static final String LICHESS = "https://lichess.org/api/user/";

    /** Records every call and every pause, and runs the call. */
    private static final class RecordingLimiter extends ChessRateLimiter {
        final List<ChessSite> calls = new ArrayList<>();
        final List<Duration> pauses = new ArrayList<>();

        RecordingLimiter() {
            super(Duration.ofMillis(10), java.time.Clock.systemUTC());
        }

        @Override
        public <T> T call(ChessSite site, Supplier<T> request) {
            calls.add(site);
            return request.get();
        }

        @Override
        public void pause(ChessSite site, Duration duration) {
            pauses.add(duration);
        }
    }

    private MockRestServiceServer server;
    private RecordingLimiter limiter;
    private HttpChessClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        limiter = new RecordingLimiter();
        client = new HttpChessClient(builder.build(), limiter);
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/enrichment/chess/" + name), StandardCharsets.UTF_8);
    }

    private void answer(String url, String fixture) throws IOException {
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.USER_AGENT, EnrichmentUserAgent.VALUE))
                .andExpect(header(HttpHeaders.ACCEPT, "application/json"))
                .andRespond(withSuccess(fixture(fixture), MediaType.APPLICATION_JSON));
    }

    private void status(String url, HttpStatus status, String body, MediaType type) {
        server.expect(requestTo(url)).andRespond(withStatus(status).body(body).contentType(type));
    }

    // --- Chess.com ---

    @Test
    void chesscomPlayerReadsTheStableIdAndTheName() throws IOException {
        answer(CHESSCOM + "hikaru", "chesscom-player.json");

        ChesscomPlayer player = client.chesscomPlayer("Hikaru");

        assertThat(player.playerId()).isEqualTo(15448422L);
        assertThat(player.username()).isEqualTo("hikaru");
        assertThat(player.url()).isEqualTo("https://www.chess.com/member/Hikaru");
        assertThat(player.title()).isEqualTo("GM");
        assertThat(player.avatarUrl()).startsWith("https://images.chesscomfiles.com/");
        assertThat(limiter.calls).containsExactly(ChessSite.CHESSCOM);
        server.verify();
    }

    @Test
    void chesscomNameIsLowercasedBecauseChesscomRedirectsCapitals() throws IOException {
        answer(CHESSCOM + "hikaru/stats", "chesscom-stats.json");

        client.chesscomStats("HiKaRu");

        server.verify();
    }

    @Test
    void chesscomStatsReadEveryRatedTimeClass() throws IOException {
        answer(CHESSCOM + "hikaru/stats", "chesscom-stats.json");

        ChesscomStats stats = client.chesscomStats("hikaru");

        assertThat(stats.modes()).containsOnlyKeys("daily", "daily960", "rapid", "bullet", "blitz");
        ChessRating blitz = stats.modes().get("blitz");
        assertThat(blitz.rating()).isEqualTo(3449);
        assertThat(blitz.deviation()).isEqualTo(30);
        assertThat(blitz.best()).isEqualTo(3503);
        assertThat(blitz.win()).isEqualTo(36268);
        assertThat(blitz.loss()).isEqualTo(5575);
        assertThat(blitz.draw()).isEqualTo(4398);
        assertThat(blitz.games()).isEqualTo(36268 + 5575 + 4398);
        assertThat(blitz.lastPlayed()).isEqualTo(Instant.ofEpochSecond(1795275975L));
        assertThat(stats.fide()).isEqualTo(2814);
    }

    @Test
    void chesscomStatsWithoutRatedGameAreEmptyNotAnError() throws IOException {
        answer(CHESSCOM + "foo/stats", "chesscom-stats-unrated.json");

        ChesscomStats stats = client.chesscomStats("foo");

        assertThat(stats.modes()).isEmpty();
        assertThat(stats.fide()).isNull();
    }

    @Test
    void chesscomEmptyObjectIsAnOutage() {
        server.expect(requestTo(CHESSCOM + "foo/stats")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chesscomStats("foo")).isInstanceOf(ChessUnavailableException.class);
    }

    @Test
    void chesscomListWhereAnObjectIsExpectedIsAnOutage() {
        server.expect(requestTo(CHESSCOM + "foo/stats")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chesscomStats("foo")).isInstanceOf(ChessUnavailableException.class);
    }

    @Test
    void chesscomHtmlIsAnOutage() {
        server.expect(requestTo(CHESSCOM + "foo")).andRespond(withSuccess("<html></html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.chesscomPlayer("foo")).isInstanceOf(ChessUnavailableException.class);
    }

    @Test
    void chesscomRecognisedNotFoundIsNotFound() throws IOException {
        status(CHESSCOM + "zzzznotauserqq123", HttpStatus.NOT_FOUND, fixture("chesscom-404.json"),
                MediaType.APPLICATION_JSON);

        assertThatThrownBy(() -> client.chesscomPlayer("zzzznotauserqq123"))
                .isInstanceOf(ChessNotFoundException.class);
    }

    @Test
    void anyOther404IsAnOutage() {
        status(CHESSCOM + "foo", HttpStatus.NOT_FOUND, "<html>gone</html>", MediaType.TEXT_HTML);

        assertThatThrownBy(() -> client.chesscomPlayer("foo")).isInstanceOf(ChessUnavailableException.class);
    }

    @Test
    void aJson404OfAnotherShapeIsAnOutage() {
        status(CHESSCOM + "foo", HttpStatus.NOT_FOUND, "{\"error\":\"Not found\"}", MediaType.APPLICATION_JSON);

        assertThatThrownBy(() -> client.chesscomPlayer("foo")).isInstanceOf(ChessUnavailableException.class);
    }

    @Test
    void aRedirectIsNeverFollowedAndIsAnOutage() {
        server.expect(requestTo(CHESSCOM + "foo/stats")).andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, CHESSCOM + "bar/stats"));

        assertThatThrownBy(() -> client.chesscomStats("foo")).isInstanceOf(ChessUnavailableException.class);
        server.verify();
    }

    @Test
    void transportNeverFollowsRedirects() {
        assertThat(HttpChessClient.httpClient().followRedirects())
                .isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
    }

    @Test
    void tooManyRequestsPausesTheSiteForItsRetryAfter() {
        server.expect(requestTo(CHESSCOM + "foo/stats")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "30"));

        assertThatThrownBy(() -> client.chesscomStats("foo")).isInstanceOf(ChessUnavailableException.class);
        assertThat(limiter.pauses).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void tooManyRequestsWithoutRetryAfterPausesAMinute() {
        server.expect(requestTo(LICHESS + "foo")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.lichessUser("foo")).isInstanceOf(ChessUnavailableException.class);
        assertThat(limiter.pauses).containsExactly(Duration.ofSeconds(60));
    }

    @Test
    void retryAfterIsCapped() {
        server.expect(requestTo(LICHESS + "foo")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "86400"));

        assertThatThrownBy(() -> client.lichessUser("foo")).isInstanceOf(ChessUnavailableException.class);
        assertThat(limiter.pauses).containsExactly(Duration.ofMinutes(10));
    }

    @Test
    void serverErrorIsAnOutageWhoseMessageCarriesNoBody() {
        server.expect(requestTo(CHESSCOM + "foo/stats")).andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                .body("secret-looking body"));

        assertThatThrownBy(() -> client.chesscomStats("foo")).isInstanceOf(ChessUnavailableException.class)
                .message().doesNotContain("secret-looking");
    }

    @Test
    void chesscomDailyGamesTellTheOpponentTheColourAndWhoseTurn() throws IOException {
        answer(CHESSCOM + "erik/games", "chesscom-games.json");

        List<ChesscomDailyGame> games = client.chesscomGames("erik");

        assertThat(games).hasSize(5);
        ChesscomDailyGame first = games.getFirst();
        assertThat(first.url()).isEqualTo("https://www.chess.com/game/daily/1067252730");
        assertThat(first.color()).isEqualTo("black");
        assertThat(first.opponent()).isEqualTo("zarasdrta");
        assertThat(first.yourTurn()).isFalse();
        assertThat(first.moveBy()).isEqualTo(Instant.ofEpochSecond(1795458668L));
        assertThat(first.rules()).isEqualTo("chess");
    }

    @Test
    void chesscomNoDailyGameIsAnEmptyList() throws IOException {
        answer(CHESSCOM + "hikaru/games", "chesscom-games-empty.json");

        assertThat(client.chesscomGames("hikaru")).isEmpty();
    }

    @Test
    void chesscomGamesWithoutTheListAreAnOutage() {
        server.expect(requestTo(CHESSCOM + "erik/games")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chesscomGames("erik")).isInstanceOf(ChessUnavailableException.class);
    }

    // --- Lichess ---

    @Test
    void lichessUserReadsRatingsCountsAndNoGameInProgress() throws IOException {
        answer(LICHESS + "DrNykterstein", "lichess-user.json");

        LichessUser user = client.lichessUser("DrNykterstein");

        assertThat(user.id()).isEqualTo("drnykterstein");
        assertThat(user.username()).isEqualTo("DrNykterstein");
        assertThat(user.title()).isEqualTo("GM");
        assertThat(user.url()).isEqualTo("https://lichess.org/@/DrNykterstein");
        // only the variants played: rapid, classical and correspondence have no game
        assertThat(user.perfs()).containsOnlyKeys("ultraBullet", "bullet", "blitz", "chess960", "atomic");
        ChessRating bullet = user.perfs().get("bullet");
        assertThat(bullet.rating()).isEqualTo(3263);
        assertThat(bullet.deviation()).isEqualTo(92);
        assertThat(bullet.progress()).isEqualTo(23);
        assertThat(bullet.provisional()).isFalse();
        assertThat(bullet.games()).isEqualTo(9585);
        assertThat(user.perfs().get("blitz").provisional()).isTrue();
        assertThat(user.games()).isEqualTo(10455);
        assertThat(user.win()).isEqualTo(7212);
        assertThat(user.loss()).isEqualTo(2409);
        assertThat(user.draw()).isEqualTo(834);
        assertThat(user.playingUrl()).isNull();
        assertThat(user.seenAt()).isEqualTo(Instant.ofEpochMilli(1794704009472L));
        assertThat(limiter.calls).containsExactly(ChessSite.LICHESS);
    }

    @Test
    void lichessUserInAGameGivesItsLink() throws IOException {
        answer(LICHESS + "novik70", "lichess-user-playing.json");

        assertThat(client.lichessUser("novik70").playingUrl()).isEqualTo("https://lichess.org/Gkmny9jn/white");
    }

    @Test
    void lichessClosedAccountIsNotFound() throws IOException {
        answer(LICHESS + "Thibault2", "lichess-user-disabled.json");

        assertThatThrownBy(() -> client.lichessUser("Thibault2")).isInstanceOf(ChessNotFoundException.class);
    }

    @Test
    void lichessRecognisedNotFoundIsNotFound() throws IOException {
        status(LICHESS + "zzzznotauserqq123", HttpStatus.NOT_FOUND, fixture("lichess-404.json"),
                MediaType.APPLICATION_JSON);

        assertThatThrownBy(() -> client.lichessUser("zzzznotauserqq123")).isInstanceOf(ChessNotFoundException.class);
    }

    @Test
    void lichessChesscomShaped404IsAnOutage() throws IOException {
        status(LICHESS + "foo", HttpStatus.NOT_FOUND, fixture("chesscom-404.json"), MediaType.APPLICATION_JSON);

        assertThatThrownBy(() -> client.lichessUser("foo")).isInstanceOf(ChessUnavailableException.class);
    }

    @Test
    void lichessUserWithoutIdIsAnOutage() {
        server.expect(requestTo(LICHESS + "foo")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.lichessUser("foo")).isInstanceOf(ChessUnavailableException.class);
    }

    @Test
    void aNameIsSentAsOneEncodedPathSegment() {
        server.expect(requestTo(LICHESS + "a%2Fb")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .body("{\"error\":\"Not found\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.lichessUser("a/b")).isInstanceOf(ChessNotFoundException.class);
        server.verify();
    }

    @Test
    void aTooBigAnswerIsAnOutage() {
        String big = "{\"games\":[" + "{\"url\":\"x\"},".repeat(HttpChessClient.MAX_BYTES / 10) + "{}]}";
        server.expect(requestTo(CHESSCOM + "erik/games")).andRespond(withSuccess(big, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chesscomGames("erik")).isInstanceOf(ChessUnavailableException.class);
    }
}
