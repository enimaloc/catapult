package fr.enimaloc.catapult.service.enrichment.rastats;

import fr.enimaloc.catapult.getter.retroachievements.RetroAchievementsApiKeyRotator;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

/** Fixtures were built from the documentation (no key was available): see the README beside them. */
class HttpRetroAchievementsClientTest {

    private static final String API = "https://retroachievements.org/API/";
    private static final String ULID = "00003EMFWR7XB8SDPEHB3K56ZQ";
    private static final RaCredentials POOL = new RaCredentials("poolkey0123456789", false);
    private static final RaCredentials PERSONAL = new RaCredentials("personalkey0123456789", true);

    private MockRestServiceServer server;
    private RetroAchievementsRateLimiter limiter;
    private RetroAchievementsApiKeyRotator rotator;
    private HttpRetroAchievementsClient client;
    private int budgetAsked;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        limiter = mock(RetroAchievementsRateLimiter.class);
        budgetAsked = 0;
        when(limiter.budget()).thenReturn(() -> {
            budgetAsked++;
            return true;
        });
        rotator = mock(RetroAchievementsApiKeyRotator.class);
        client = new HttpRetroAchievementsClient(builder.build(), limiter, Optional.of(rotator));
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("enrichment/retroachievements/" + name + ".json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void keyOnlyInTheYQueryParam() throws IOException {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(headerDoesNotExist("X-API-Key"))
                .andExpect(request -> request.getHeaders().forEach((name, values) ->
                        assertThat(values).noneMatch(v -> v.contains("poolkey0123456789"))))
                .andExpect(header(HttpHeaders.ACCEPT, "application/json"))
                .andRespond(withSuccess(fixture("profile"), MediaType.APPLICATION_JSON));

        client.profile("Scott", POOL);

        server.verify();
        verify(rotator).onCall("poolkey0123456789");
    }

    @Test
    void aPersonalKeyIsNotCountedAgainstThePool() throws IOException {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=personalkey0123456789"))
                .andRespond(withSuccess(fixture("profile"), MediaType.APPLICATION_JSON));

        client.profile("Scott", PERSONAL);

        verify(rotator, never()).onCall(any());
    }

    @Test
    void profileIsRead() throws IOException {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withSuccess(fixture("profile"), MediaType.APPLICATION_JSON));

        RaUser user = client.profile("Scott", POOL).orElseThrow();

        assertThat(user.user()).isEqualTo("Scott");
        assertThat(user.ulid()).isEqualTo(ULID);
        assertThat(user.picUrl()).isEqualTo("https://retroachievements.org/UserPic/Scott.png");
        assertThat(user.totalPoints()).isEqualTo(15000);
        assertThat(user.totalSoftcorePoints()).isEqualTo(400);
        assertThat(user.totalTruePoints()).isEqualTo(52000);
        assertThat(user.richPresence()).isEqualTo("Playing Super Mario World");
        assertThat(user.lastGameId()).isEqualTo(228);
        assertThat(user.untracked()).isFalse();
        assertThat(budgetAsked).as("every HTTP request is charged to the limiter").isEqualTo(1);
    }

    @Test
    void anUntrackedProfileIsFlagged() {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withSuccess("{\"User\":\"Scott\",\"ULID\":\"U1\",\"Untracked\":1}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.profile("Scott", POOL).orElseThrow().untracked()).isTrue();
    }

    @Test
    void anUnknownUserIsAbsent() {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=ghost&y=poolkey0123456789"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Not Found.\"}"));
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=ghost2&y=poolkey0123456789"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.profile("ghost", POOL)).isEmpty();
        assertThat(client.profile("ghost2", POOL)).as("no ULID, no account").isEmpty();
    }

    @Test
    void recentlyPlayedIsRead() throws IOException {
        server.expect(once(), requestTo(API + "API_GetUserRecentlyPlayedGames.php?u=" + ULID + "&c=10&y=poolkey0123456789"))
                .andRespond(withSuccess(fixture("recently-played"), MediaType.APPLICATION_JSON));

        List<RaRecentGame> games = client.recentlyPlayed(ULID, 10, POOL);

        assertThat(games).extracting(RaRecentGame::title)
                .containsExactly("Super Mario World", "Sonic the Hedgehog");
        RaRecentGame first = games.getFirst();
        assertThat(first.gameId()).isEqualTo(228);
        assertThat(first.consoleName()).isEqualTo("SNES/Super Famicom");
        assertThat(first.iconUrl()).isEqualTo("https://retroachievements.org/Images/112233.png");
        assertThat(first.lastPlayed()).isEqualTo(Instant.parse("2026-11-22T10:00:00Z"));
        assertThat(first.achievementsTotal()).isEqualTo(96);
        assertThat(first.numAchieved()).isEqualTo(40);
        assertThat(first.numAchievedHardcore()).isEqualTo(38);
        assertThat(first.scoreAchieved()).isEqualTo(300);
        assertThat(first.scoreAchievedHardcore()).isEqualTo(280);
        assertThat(first.possibleScore()).isEqualTo(600);
    }

    @Test
    void anEmptyOrObjectAnswerGivesNoRecentGames() {
        server.expect(once(), requestTo(API + "API_GetUserRecentlyPlayedGames.php?u=" + ULID + "&c=5&y=poolkey0123456789"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(API + "API_GetUserRecentlyPlayedGames.php?u=" + ULID + "&c=6&y=poolkey0123456789"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.recentlyPlayed(ULID, 5, POOL)).isEmpty();
        assertThat(client.recentlyPlayed(ULID, 6, POOL)).isEmpty();
    }

    @Test
    void achievementsObjectIsReadInDisplayOrder() throws IOException {
        server.expect(once(), requestTo(API + "API_GetGameInfoAndUserProgress.php?g=228&u=" + ULID
                        + "&a=1&y=poolkey0123456789"))
                .andRespond(withSuccess(fixture("game-progress"), MediaType.APPLICATION_JSON));

        RaGameProgress progress = client.progress(228, ULID, POOL).orElseThrow();

        assertThat(progress.title()).isEqualTo("Super Mario World");
        assertThat(progress.consoleName()).isEqualTo("SNES/Super Famicom");
        assertThat(progress.numAchievements()).isEqualTo(3);
        assertThat(progress.numDistinctPlayers()).isEqualTo(1000);
        assertThat(progress.numDistinctPlayersHardcore()).isEqualTo(800);
        assertThat(progress.numAwardedToUser()).isEqualTo(2);
        assertThat(progress.numAwardedToUserHardcore()).isEqualTo(1);
        assertThat(progress.userCompletion()).isEqualTo("66.67%");
        assertThat(progress.userTotalPlaytime()).isEqualTo(7200);
        assertThat(progress.highestAwardKind()).isEqualTo("beaten-hardcore");
        assertThat(progress.achievements()).extracting(RaAchievement::title)
                .containsExactly("First", "Second", "Third");
        RaAchievement first = progress.achievements().getFirst();
        assertThat(first.id()).isEqualTo(3);
        assertThat(first.points()).isEqualTo(5);
        assertThat(first.trueRatio()).isEqualTo(7);
        assertThat(first.badgeUrl()).isEqualTo("https://media.retroachievements.org/Badge/00003.png");
        assertThat(first.type()).isEqualTo("progression");
        assertThat(first.numAwarded()).isEqualTo(900);
        assertThat(first.numAwardedHardcore()).isEqualTo(700);
        assertThat(first.dateEarned()).isEqualTo(Instant.parse("2026-11-20T12:00:00Z"));
        assertThat(first.dateEarnedHardcore()).isEqualTo(Instant.parse("2026-11-20T12:00:00Z"));
        assertThat(progress.achievements().get(1).dateEarnedHardcore()).isNull();
        assertThat(progress.achievements().get(2).dateEarned()).as("no DateEarned: still locked").isNull();
        assertThat(progress.achievements().get(2).type()).isNull();
    }

    @Test
    void aGameWithoutAnIdIsAbsent() {
        server.expect(once(), requestTo(API + "API_GetGameInfoAndUserProgress.php?g=7&u=" + ULID
                        + "&a=1&y=poolkey0123456789"))
                .andRespond(withSuccess("{\"Title\":null}", MediaType.APPLICATION_JSON));

        assertThat(client.progress(7, ULID, POOL)).isEmpty();
    }

    @Test
    void recentAchievementsAreRead() throws IOException {
        server.expect(once(), requestTo(API + "API_GetUserRecentAchievements.php?u=" + ULID + "&m=1440&y=poolkey0123456789"))
                .andRespond(withSuccess(fixture("recent-achievements"), MediaType.APPLICATION_JSON));

        List<RaAchievement> recent = client.recentAchievements(ULID, 1440, POOL);

        assertThat(recent).extracting(RaAchievement::title).containsExactly("First", "Second");
        assertThat(recent.getFirst().dateEarned()).isEqualTo(Instant.parse("2026-11-22T10:05:00Z"));
        assertThat(recent.getFirst().dateEarnedHardcore()).isEqualTo(Instant.parse("2026-11-22T10:05:00Z"));
        assertThat(recent.get(1).dateEarnedHardcore()).as("softcore unlock").isNull();
        assertThat(recent.getFirst().gameId()).isEqualTo(228);
        assertThat(recent.getFirst().gameTitle()).isEqualTo("Super Mario World");
    }

    @Test
    void unauthenticatedIsKeyRejected() throws IOException {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("unauthenticated")));

        assertThatThrownBy(() -> client.profile("Scott", POOL)).isInstanceOf(RaKeyRejectedException.class);

        verify(rotator).onKeyRejected("poolkey0123456789", Duration.ofHours(1));
    }

    @Test
    void aRejectedPersonalKeyLeavesThePoolAlone() {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=personalkey0123456789"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.profile("Scott", PERSONAL)).isInstanceOf(RaKeyRejectedException.class);

        verify(rotator, never()).onKeyRejected(any(), any());
    }

    @Test
    void theExceptionsNeverCarryTheKey() {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.profile("Scott", POOL)).hasMessageNotContaining("poolkey0123456789");
        assertThatThrownBy(() -> client.profile("Scott", POOL)).hasMessageNotContaining("poolkey0123456789");
    }

    @Test
    void redirectIsNeverFollowed() {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION,
                        "https://evil.example/?y=poolkey0123456789"));

        assertThatThrownBy(() -> client.profile("Scott", POOL)).isInstanceOf(RaUnavailableException.class);

        server.verify();
    }

    @Test
    void garbled200IsAnError() {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.profile("Scott", POOL)).isInstanceOf(RaUnavailableException.class);
        assertThatThrownBy(() -> client.profile("Scott", POOL)).isInstanceOf(RaUnavailableException.class);
    }

    @Test
    void status429PausesTheSource() {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "30"));
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.profile("Scott", POOL)).isInstanceOf(RaUnavailableException.class);
        verify(limiter).pause(Duration.ofSeconds(30));
        assertThatThrownBy(() -> client.profile("Scott", POOL)).isInstanceOf(RaUnavailableException.class);
        verify(limiter).pause(Duration.ofSeconds(60));
    }

    @Test
    void otherErrorsAreUnavailable() {
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.profile("Scott", POOL)).isInstanceOf(RaUnavailableException.class);
        assertThatThrownBy(() -> client.profile("Scott", POOL)).isInstanceOf(RaUnavailableException.class);
    }

    @Test
    void aPausedSourceSendsNothing() {
        when(limiter.paused()).thenReturn(true);

        assertThatThrownBy(() -> client.profile("Scott", POOL)).isInstanceOf(RaUnavailableException.class);

        server.verify();
    }

    @Test
    void aRefusedBudgetSendsNothing() {
        when(limiter.budget()).thenReturn(new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return false;
            }

            @Override
            public Duration retryAfter() {
                return Duration.ofSeconds(5);
            }
        });

        assertThatThrownBy(() -> client.profile("Scott", POOL)).isInstanceOf(UpstreamBudgetExceededException.class);

        server.verify();
    }

    @Test
    void withoutAPoolTheClientStillWorks() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer other = MockRestServiceServer.bindTo(builder).build();
        HttpRetroAchievementsClient noPool =
                new HttpRetroAchievementsClient(builder.build(), limiter, Optional.empty());
        other.expect(once(), requestTo(API + "API_GetUserProfile.php?u=Scott&y=poolkey0123456789"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> noPool.profile("Scott", POOL)).isInstanceOf(RaKeyRejectedException.class);
    }

    @Test
    void transportNeverFollowsARedirectItself() throws IOException {
        assertFollowsNoRedirect(HttpRetroAchievementsClient.requestFactory());
    }

    @Test
    void theInjectedConstructorUsesTheNonRedirectingTransportAndAUserAgent() throws IOException {
        RestClient.Builder builder = spy(RestClient.builder());
        ArgumentCaptor<ClientHttpRequestFactory> factory = ArgumentCaptor.forClass(ClientHttpRequestFactory.class);

        new HttpRetroAchievementsClient(builder, limiter, Optional.empty(), "https://catapult.example");

        verify(builder).requestFactory(factory.capture());
        verify(builder).defaultHeader(HttpHeaders.USER_AGENT, "Catapult/1.0 (+https://catapult.example)");
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
