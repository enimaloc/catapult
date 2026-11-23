package fr.enimaloc.catapult.service.enrichment.faceit;

import fr.enimaloc.catapult.domain.faceit.FaceitApiKeyEntry;
import fr.enimaloc.catapult.getter.faceit.FaceitApiKeyRotator;
import fr.enimaloc.catapult.repository.faceit.FaceitApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpFaceitClientTest {

    static final String KEY = "12345678-abcd-4ef0-9abc-0123456789ab";
    static final String OTHER = "87654321-abcd-4ef0-9abc-0123456789ab";
    static final String ID = "5ea07280-2399-4c7e-88ab-f2f7db0c449f";
    static final String API = "https://open.faceit.com/data/v4";

    private MockRestServiceServer server;
    private FaceitApiKeyRepository repository;
    private FaceitApiKeyRotator rotator;
    private FaceitRateLimiter limiter;
    private final AtomicInteger acquired = new AtomicInteger();
    private boolean allow = true;
    private HttpFaceitClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        repository = mock(FaceitApiKeyRepository.class);
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new FaceitApiKeyEntry(KEY)));
        rotator = new FaceitApiKeyRotator(repository, new SimpleMeterRegistry());
        limiter = mock(FaceitRateLimiter.class);
        FetchBudget budget = () -> {
            acquired.incrementAndGet();
            return allow;
        };
        when(limiter.budget()).thenReturn(budget);
        client = new HttpFaceitClient(builder.build(), rotator, limiter, "Catapult/1.0 (+https://example.test)");
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/enrichment/faceit/" + name), StandardCharsets.UTF_8);
    }

    private void json(String url, String fixture) throws IOException {
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY))
                .andExpect(header(HttpHeaders.USER_AGENT, "Catapult/1.0 (+https://example.test)"))
                .andRespond(withSuccess(fixture(fixture), MediaType.APPLICATION_JSON));
    }

    @Test
    void playerByNickname_readsTheIdAndTheCs2Profile_withTheKeyInTheHeaderOnly() throws IOException {
        json(API + "/players?nickname=Catapult", "player.json");

        var player = client.playerByNickname("Catapult");

        assertThat(player.playerId()).isEqualTo(ID);
        assertThat(player.nickname()).isEqualTo("Catapult");
        assertThat(player.country()).isEqualTo("fr");
        assertThat(player.cs2()).isNotNull();
        assertThat(player.cs2().skillLevel()).isEqualTo(8);
        assertThat(player.cs2().elo()).isEqualTo(1850);
        assertThat(player.cs2().region()).isEqualTo("EU");
        assertThat(acquired).hasValue(1);
        assertThat(rotator.usage(KEY).windowCalls()).isEqualTo(1);
        server.verify();
    }

    @Test
    void aNicknameWithSigns_isEncodedInTheQuery() throws IOException {
        server.expect(requestTo(API + "/players?nickname=a%26b%3Dc"))
                .andRespond(withSuccess(fixture("player.json"), MediaType.APPLICATION_JSON));

        client.playerByNickname("a&b=c");

        server.verify();
    }

    @Test
    void aPlayerWithoutCs2_hasNoCs2Profile() throws IOException {
        json(API + "/players/" + ID, "player-no-cs2.json");

        assertThat(client.player(ID).cs2()).isNull();
    }

    @Test
    void faceitsJson404_isNotFound_anyOther404IsAnError() throws IOException {
        server.expect(requestTo(startsWith(API))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("player-404.json")));
        server.expect(requestTo(startsWith(API)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML).body("<html>nope</html>"));
        server.expect(requestTo(startsWith(API)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("{}"));
        server.expect(requestTo(startsWith(API))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("forbidden.json")));

        assertThatThrownBy(() -> client.playerByNickname("x")).isInstanceOf(FaceitNotFoundException.class);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.playerByNickname("x")).isInstanceOf(FaceitUnavailableException.class);
        }
        server.verify();
    }

    @Test
    void anUnreadableOrWrongShaped2xx_isAnError() {
        for (String body : List.of("{}", "[]", "<html></html>", "{\"nickname\":\"x\"}", "",
                "{\"player_id\":\"p\",\"nickname\":\"n\",\"games\":[]}")) {
            server.expect(requestTo(startsWith(API))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> client.playerByNickname("x")).isInstanceOf(FaceitUnavailableException.class);
        }
        server.verify();
    }

    @Test
    void aRedirectIsNeverFollowed_andIsAnError() {
        server.expect(requestTo(startsWith(API)))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://evil.example/steal"));

        assertThatThrownBy(() -> client.player(ID)).isInstanceOf(FaceitUnavailableException.class);
        server.verify();
    }

    @Test
    void theTransport_followsNoRedirect() {
        assertThat(HttpFaceitClient.httpClient().followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
    }

    @Test
    void aRefusedKey_isSetAside_andTheCallTriedOnceWithAnother() throws IOException {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new FaceitApiKeyEntry(KEY),
                new FaceitApiKeyEntry(OTHER)));
        rotator.refreshKeys();
        server.expect(requestTo(startsWith(API))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(startsWith(API)))
                .andRespond(withSuccess(fixture("player.json"), MediaType.APPLICATION_JSON));

        assertThat(client.player(ID).nickname()).isEqualTo("Catapult");

        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
        assertThat(acquired).hasValue(2);
        server.verify();
    }

    @Test
    void theOnlyKeyRefused_isAnOutage_withoutTheKeyInTheMessage() throws IOException {
        server.expect(requestTo(startsWith(API))).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("forbidden.json")));

        assertThatThrownBy(() -> client.player(ID))
                .isInstanceOf(FaceitUnavailableException.class).hasMessageNotContaining(KEY);
        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
        server.verify();
    }

    @Test
    void anEmptyPool_isAnOutage_withoutAnyRequest() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of());
        rotator.refreshKeys();

        assertThatThrownBy(() -> client.player(ID)).isInstanceOf(FaceitUnavailableException.class);
        assertThat(acquired).hasValue(0);
    }

    @Test
    void aRefusedBudget_sendsNothing() {
        allow = false;

        assertThatThrownBy(() -> client.player(ID)).isInstanceOf(FaceitUnavailableException.class);
        server.verify();
    }

    @Test
    void aPausedLimiter_sendsNothing() {
        when(limiter.paused()).thenReturn(true);

        assertThatThrownBy(() -> client.player(ID)).isInstanceOf(FaceitUnavailableException.class);
        assertThat(acquired).hasValue(0);
    }

    @Test
    void a429_pausesTheLimiter_cappingItsRetryAfter_andSetsTheKeyAside() {
        server.expect(requestTo(startsWith(API)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "86400"));

        assertThatThrownBy(() -> client.player(ID)).isInstanceOf(FaceitUnavailableException.class);

        verify(limiter).pause(HttpFaceitClient.MAX_PAUSE);
        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
    }

    @Test
    void theRetryAfter_defaultsToAMinute_andIsCapped() {
        assertThat(HttpFaceitClient.retryAfter(null)).isEqualTo(Duration.ofSeconds(60));
        assertThat(HttpFaceitClient.retryAfter("soon")).isEqualTo(Duration.ofSeconds(60));
        assertThat(HttpFaceitClient.retryAfter("0")).isEqualTo(Duration.ofSeconds(60));
        assertThat(HttpFaceitClient.retryAfter("5")).isEqualTo(Duration.ofSeconds(5));
        assertThat(HttpFaceitClient.retryAfter("99999")).isEqualTo(HttpFaceitClient.MAX_PAUSE);
    }

    @Test
    void a5xx_isAnOutage_andDoesNotPause() {
        server.expect(requestTo(startsWith(API))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.player(ID)).isInstanceOf(FaceitUnavailableException.class);
        verify(limiter, never()).pause(any());
    }

    @Test
    void lifetime_readsTheNumbersFaceitSendsAsText() throws IOException {
        json(API + "/players/" + ID + "/stats/cs2", "lifetime.json");

        var lifetime = client.lifetime(ID, "cs2");

        assertThat(lifetime.matches()).isEqualTo(420);
        assertThat(lifetime.wins()).isEqualTo(231);
        assertThat(lifetime.winRatePercent()).isEqualTo(55);
        assertThat(lifetime.averageKd()).isEqualTo(1.12);
        assertThat(lifetime.averageHeadshotsPercent()).isEqualTo(48);
        assertThat(lifetime.currentWinStreak()).isEqualTo(2);
        assertThat(lifetime.longestWinStreak()).isEqualTo(9);
        assertThat(lifetime.recentResults()).containsExactly(true, true, false, true, false);
    }

    @Test
    void lifetime_withoutItsObject_isAnError_andItsJson404IsNotFound() throws IOException {
        server.expect(requestTo(startsWith(API)))
                .andRespond(withSuccess("{\"player_id\":\"p\",\"segments\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(API))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body(fixture("player-404.json")));

        assertThatThrownBy(() -> client.lifetime(ID, "cs2")).isInstanceOf(FaceitUnavailableException.class);
        assertThatThrownBy(() -> client.lifetime(ID, "cs2")).isInstanceOf(FaceitNotFoundException.class);
    }

    @Test
    void lastMatch_readsTheMapScoreAndResult() throws IOException {
        json(API + "/players/" + ID + "/games/cs2/stats?offset=0&limit=1", "last-match.json");

        var match = client.lastMatch(ID, "cs2").orElseThrow();

        assertThat(match.matchId()).isEqualTo("1-6c0d2a6f-0000-4000-8000-000000000001");
        assertThat(match.map()).isEqualTo("de_mirage");
        assertThat(match.score()).isEqualTo("13 / 9");
        assertThat(match.won()).isTrue();
        assertThat(match.kills()).isEqualTo(21);
        assertThat(match.deaths()).isEqualTo(15);
        assertThat(match.kd()).isEqualTo(1.4);
        assertThat(match.finishedAt()).isEqualTo(Instant.ofEpochMilli(1795380520000L));
    }

    @Test
    void lastMatch_isEmptyWithoutAnyMatch_andAnErrorWithoutItsList() throws IOException {
        json(API + "/players/" + ID + "/games/cs2/stats?offset=0&limit=1", "last-match-empty.json");
        server.expect(requestTo(startsWith(API)))
                .andRespond(withSuccess("{\"start\":0}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(API)))
                .andRespond(withSuccess("{\"items\":[{\"stats\":[]}]}", MediaType.APPLICATION_JSON));

        assertThat(client.lastMatch(ID, "cs2")).isEmpty();
        assertThatThrownBy(() -> client.lastMatch(ID, "cs2")).isInstanceOf(FaceitUnavailableException.class);
        assertThatThrownBy(() -> client.lastMatch(ID, "cs2")).isInstanceOf(FaceitUnavailableException.class);
    }

    @Test
    void aBodyTooBig_isRead_nomore() throws IOException {
        byte[] big = new byte[HttpFaceitClient.MAX_BYTES + 10];
        assertThat(HttpFaceitClient.readLimited(new ByteArrayInputStream(big), HttpFaceitClient.MAX_BYTES)).isNull();
        assertThat(HttpFaceitClient.readLimited(new ByteArrayInputStream(new byte[3]), 3)).hasSize(3);
    }
}
