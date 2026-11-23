package fr.enimaloc.catapult.service.enrichment.osu;

import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.osu.OsuCredentialsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpOsuClientTest {

    private static final String SECRET = "client-secret-value";
    private static final String ME = "https://osu.ppy.sh/api/v2/me";
    private static final String TOKEN = "https://osu.ppy.sh/oauth/token";
    private static final String RECENT =
            "https://osu.ppy.sh/api/v2/users/7562902/scores/recent?include_fails=0&limit=5&mode=osu";
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private MockRestServiceServer server;
    private OsuCredentialsService credentials;
    private OsuRateLimiter limiter;
    private final AtomicInteger acquired = new AtomicInteger();
    private HttpOsuClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        credentials = mock(OsuCredentialsService.class);
        when(credentials.current()).thenReturn(Optional.of(new OsuCredentialsService.Credentials("12345", SECRET)));
        limiter = mock(OsuRateLimiter.class);
        when(limiter.budget()).thenReturn((FetchBudget) () -> {
            acquired.incrementAndGet();
            return true;
        });
        client = new HttpOsuClient(builder.build(), credentials, limiter, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/enrichment/osu/" + name), StandardCharsets.UTF_8);
    }

    // --- OAuth ---

    @Test
    void refresh_sendsTheSecretAndTheTokenInTheBody_neverInTheUrlNorAHeader() throws IOException {
        server.expect(requestTo(TOKEN)).andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(content().string("client_id=12345&client_secret=client-secret-value"
                        + "&grant_type=refresh_token&refresh_token=old%2Brefresh&scope=identify+public"))
                .andRespond(withSuccess(fixture("token.json"), MediaType.APPLICATION_JSON));

        OsuData.TokenGrant grant = client.refresh("old+refresh");

        assertThat(grant.accessToken()).isEqualTo("fake-access-token");
        assertThat(grant.refreshToken()).isEqualTo("fake-refresh-token");
        assertThat(grant.expiresAt()).isEqualTo(NOW.plusSeconds(86_400));
        assertThat(grant.toString()).doesNotContain("eyJ0").doesNotContain("def50200");
        assertThat(acquired).hasValue(1);
        server.verify();
    }

    @Test
    void refresh_aRefusedRefreshToken_isRefused_butAnyOtherFailureIsAnOutage() throws IOException {
        server.expect(requestTo(TOKEN)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("token-invalid-grant.json")));
        server.expect(requestTo(TOKEN)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("token-invalid-request.json")));
        server.expect(requestTo(TOKEN)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("token-invalid-client.json")));
        server.expect(requestTo(TOKEN)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN)).andRespond(withSuccess(fixture("not-json.html"), MediaType.TEXT_HTML));
        server.expect(requestTo(TOKEN))
                .andRespond(withStatus(HttpStatus.FOUND).location(URI.create("https://osu.ppy.sh/home")));
        server.expect(requestTo(TOKEN)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(OsuExceptions.RefreshRefused.class);
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(OsuExceptions.RefreshRefused.class);
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(OsuUnavailableException.class);
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(OsuUnavailableException.class);
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(OsuUnavailableException.class);
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(OsuUnavailableException.class)
                .hasMessageContaining("redirect");
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(OsuUnavailableException.class)
                .hasMessageNotContaining(SECRET);
        server.verify();
    }

    @Test
    void withoutAnApplication_noRenewalIsSent() {
        when(credentials.current()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(OsuUnavailableException.class);
        assertThat(acquired).hasValue(0);
        server.verify();
    }

    @Test
    void aRefusedLimiter_sendsNothing() {
        when(limiter.budget()).thenReturn((FetchBudget) () -> false);

        assertThatThrownBy(() -> client.me("t")).isInstanceOf(OsuUnavailableException.class);
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(OsuUnavailableException.class);
        server.verify();
    }

    // --- reads ---

    @Test
    void me_readsTheProfileOnTheDefaultRuleset_withTheMembersTokenInTheHeader() throws IOException {
        server.expect(requestTo(ME)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer member-token"))
                .andExpect(header("x-api-version", "20220705"))
                .andRespond(withSuccess(fixture("me.json"), MediaType.APPLICATION_JSON));

        OsuData.Me me = client.me("member-token");

        assertThat(me.id()).isEqualTo(7_562_902L);
        assertThat(me.username()).isEqualTo("Inspector");
        assertThat(me.playmode()).isEqualTo("osu");
        assertThat(me.countryCode()).isEqualTo("FR");
        OsuData.Statistics s = me.statistics();
        assertThat(s.pp()).isEqualTo(4321.5);
        assertThat(s.globalRank()).isEqualTo(12_345);
        assertThat(s.countryRank()).isEqualTo(678);
        assertThat(s.accuracy()).isEqualTo(98.7654);
        assertThat(s.playCount()).isEqualTo(23_456L);
        assertThat(s.playTimeSeconds()).isEqualTo(1_234_567L);
        assertThat(s.level()).isEqualTo(101);
        assertThat(s.ranked()).isTrue();
        assertThat(me.rulesets()).containsOnlyKeys("osu", "taiko", "fruits", "mania");
        assertThat(me.rulesets().get("taiko").globalRank()).isNull();
        assertThat(me.rulesets().get("fruits").playTimeSeconds()).isNull();
        assertThat(acquired).hasValue(1);
        server.verify();
    }

    @Test
    void recentScores_readsTheLazerFormat_andNeverTrustsAForeignBeatmapLink() throws IOException {
        server.expect(requestTo(RECENT)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer member-token"))
                .andRespond(withSuccess(fixture("scores-recent.json"), MediaType.APPLICATION_JSON));

        List<OsuData.Score> scores = client.recentScores(7_562_902L, "osu", 5, "member-token");

        assertThat(scores).hasSize(2);
        OsuData.Score first = scores.getFirst();
        assertThat(first.ruleset()).isEqualTo("osu");
        assertThat(first.rank()).isEqualTo("S");
        assertThat(first.pp()).isEqualTo(256.43);
        assertThat(first.accuracy()).isCloseTo(97.512, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(first.maxCombo()).isEqualTo(790);
        assertThat(first.mods()).containsExactly("HD", "CL");
        assertThat(first.endedAt()).isEqualTo(Instant.parse("2026-11-22T19:58:12Z"));
        assertThat(first.beatmap().title()).isEqualTo("Some Song");
        assertThat(first.beatmap().artist()).isEqualTo("Some Artist");
        assertThat(first.beatmap().version()).isEqualTo("Insane");
        assertThat(first.beatmap().starRating()).isEqualTo(5.42);
        assertThat(first.beatmap().url()).isEqualTo("https://osu.ppy.sh/beatmaps/1001");
        assertThat(scores.get(1).pp()).isNull();
        assertThat(scores.get(1).beatmap().url()).isEqualTo("https://osu.ppy.sh/b/1000");
        server.verify();
    }

    @Test
    void anEmptyRecentList_isNoScore_notAnError() {
        server.expect(requestTo(RECENT)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.recentScores(7_562_902L, "osu", 5, "t")).isEmpty();
    }

    @Test
    void aRefusedToken_isAuthRefused() throws IOException {
        server.expect(requestTo(ME)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("unauthenticated.json")));

        assertThatThrownBy(() -> client.me("t")).isInstanceOf(OsuExceptions.AuthRefused.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"id\":1}", "{\"id\":\"1\",\"statistics\":{}}", "null"})
    void aWrongShapedProfile_isAnOutage(String body) {
        server.expect(requestTo(ME)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.me("t")).isInstanceOf(OsuUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[{}]", "[{\"id\":\"x\"}]"})
    void wrongShapedScores_areAnOutage(String body) {
        server.expect(requestTo(RECENT)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.recentScores(7_562_902L, "osu", 5, "t"))
                .isInstanceOf(OsuUnavailableException.class);
    }

    @Test
    void htmlRedirectAnd404_areOutages_neverAbsent() throws IOException {
        server.expect(requestTo(ME)).andRespond(withSuccess(fixture("not-json.html"), MediaType.TEXT_HTML));
        server.expect(requestTo(ME))
                .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY).location(URI.create("https://osu.ppy.sh/")));
        server.expect(requestTo(ME)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":null}"));
        server.expect(requestTo(ME)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.me("t")).isInstanceOf(OsuUnavailableException.class);
        }
        server.verify();
    }

    @Test
    void tooBigAnAnswer_isAnOutage() {
        server.expect(requestTo(ME)).andRespond(withSuccess("[" + "0,".repeat(HttpOsuClient.MAX_BYTES / 2) + "0]",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.me("t")).isInstanceOf(OsuUnavailableException.class)
                .hasMessageContaining("too big");
    }

    @Test
    void a429_pausesEveryCall_forItsRetryAfter_capped() {
        server.expect(requestTo(ME)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "30"));
        server.expect(requestTo(ME)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "86400"));
        server.expect(requestTo(TOKEN)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.me("t")).isInstanceOf(OsuUnavailableException.class);
        verify(limiter).pause(Duration.ofSeconds(30));
        assertThatThrownBy(() -> client.me("t")).isInstanceOf(OsuUnavailableException.class);
        verify(limiter).pause(HttpOsuClient.MAX_PAUSE);
        assertThatThrownBy(() -> client.refresh("r")).isInstanceOf(OsuUnavailableException.class);
        verify(limiter).pause(Duration.ofSeconds(60));
        server.verify();
    }

    @Test
    void aSuccess_neverPauses() throws IOException {
        server.expect(requestTo(ME)).andRespond(withSuccess(fixture("me.json"), MediaType.APPLICATION_JSON));

        client.me("t");

        verify(limiter, never()).pause(any());
    }

    @Test
    void anUnexpectedRuleset_neverReachesTheUrl() {
        assertThatThrownBy(() -> client.recentScores(1L, "osu&x=1", 5, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }
}
