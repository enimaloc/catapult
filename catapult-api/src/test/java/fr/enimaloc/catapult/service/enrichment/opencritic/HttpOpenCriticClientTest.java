package fr.enimaloc.catapult.service.enrichment.opencritic;

import fr.enimaloc.catapult.getter.opencritic.OpenCriticApiKeyRotator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpOpenCriticClientTest {

    private static final String BASE = "https://opencritic-api.p.rapidapi.com";

    private MockRestServiceServer server;
    private OpenCriticApiKeyRotator rotator;
    private HttpOpenCriticClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        rotator = mock(OpenCriticApiKeyRotator.class);
        client = new HttpOpenCriticClient(builder, Optional.of(rotator));
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("enrichment/opencritic/" + name + ".json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void search_sendsRapidApiHeadersAndReadsHits() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(BASE + "/game/search?criteria=Hades"))
                .andExpect(header("X-RapidAPI-Key", "keyA"))
                .andExpect(header("X-RapidAPI-Host", "opencritic-api.p.rapidapi.com"))
                .andRespond(withSuccess(fixture("search"), MediaType.APPLICATION_JSON));

        assertThat(client.search("Hades")).containsExactly(
                new OpenCriticClient.Hit(463, "Hades"), new OpenCriticClient.Hit(14015, "Hades II"));
        verify(rotator).onCall("keyA");
        server.verify();
    }

    @Test
    void game_roundsScoresAndBuildsUrl() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(BASE + "/game/463"))
                .andRespond(withSuccess(fixture("game"), MediaType.APPLICATION_JSON));

        OpenCriticGame game = client.game(463).orElseThrow();

        assertThat(game).isEqualTo(new OpenCriticGame(463, "Hades", 93, 98, "Mighty", 78,
                "https://opencritic.com/game/463/hades"));
    }

    @Test
    void negativeScoreIsNull() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(BASE + "/game/9001"))
                .andRespond(withSuccess(fixture("game-unrated"), MediaType.APPLICATION_JSON));

        OpenCriticGame game = client.game(9001).orElseThrow();

        assertThat(game.topCriticScore()).isNull();
        assertThat(game.percentRecommended()).isNull();
        assertThat(game.numReviews()).isZero();
    }

    @Test
    void game_404IsAbsent() {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(BASE + "/game/1")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.game(1)).isEmpty();
    }

    @Test
    void tooManyRequests_blocksKeyAndRetriesWithNext() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"), Optional.of("keyB"));
        server.expect(once(), requestTo(BASE + "/game/search?criteria=Hades"))
                .andExpect(header("X-RapidAPI-Key", "keyA"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "30"));
        server.expect(once(), requestTo(BASE + "/game/search?criteria=Hades"))
                .andExpect(header("X-RapidAPI-Key", "keyB"))
                .andRespond(withSuccess(fixture("search"), MediaType.APPLICATION_JSON));

        List<OpenCriticClient.Hit> hits = client.search("Hades");

        assertThat(hits).hasSize(2);
        verify(rotator).block("keyA", Duration.ofSeconds(30));
    }

    @Test
    void isConfigured_followsThePool() {
        when(rotator.hasKeys()).thenReturn(true);
        assertThat(client.isConfigured()).isTrue();
        assertThat(new HttpOpenCriticClient(RestClient.builder(), Optional.empty()).isConfigured()).isFalse();
    }
}
