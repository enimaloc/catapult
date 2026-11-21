package fr.enimaloc.catapult.service.enrichment.steamgriddb;

import fr.enimaloc.catapult.getter.steamgriddb.SteamGridDbApiKeyRotator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SteamGridDbClientTest {

    private static final String BASE = "https://www.steamgriddb.com/api/v2";

    private MockRestServiceServer server;
    private SteamGridDbApiKeyRotator rotator;
    private HttpSteamGridDbClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        rotator = mock(SteamGridDbApiKeyRotator.class);
        client = new HttpSteamGridDbClient(builder, Optional.of(rotator));
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("enrichment/sgdb-" + name + ".json").getContentAsString(StandardCharsets.UTF_8);
    }

    private void expectAssets(String key, long id) throws IOException {
        for (String kind : new String[]{"grids", "heroes", "logos", "icons"}) {
            server.expect(once(), requestTo(BASE + "/" + kind + "/game/" + id))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                    .andRespond(withSuccess(fixture(kind), MediaType.APPLICATION_JSON));
        }
    }

    @Test
    void bySteamAppId_resolvesGameThenAssets() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(BASE + "/games/steam/570"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer keyA"))
                .andRespond(withSuccess(fixture("game-steam"), MediaType.APPLICATION_JSON));
        expectAssets("keyA", 5248);

        SteamGridDbAssets assets = client.fetchBySteamAppId("570").orElseThrow();

        assertThat(assets.gameId()).isEqualTo(5248);
        assertThat(assets.name()).isEqualTo("Dota 2");
        assertThat(assets.url()).isEqualTo("https://www.steamgriddb.com/game/5248");
        assertThat(assets.grids()).hasSize(2);
        assertThat(assets.bestGrid()).isEqualTo(new SteamGridDbAssets.Asset(
                "https://cdn2.steamgriddb.com/grid/a.png", "https://cdn2.steamgriddb.com/thumb/grid-a.jpg",
                600, 900, "alternate"));
        assertThat(assets.bestHero().width()).isEqualTo(1920);
        assertThat(assets.bestLogo().style()).isEqualTo("official");
        assertThat(assets.bestIcon().height()).isEqualTo(256);
        verify(rotator, times(5)).onCall("keyA");
        server.verify();
    }

    @Test
    void byName_usesAutocompleteFirstResult() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(BASE + "/search/autocomplete/dota%202"))
                .andRespond(withSuccess(fixture("search"), MediaType.APPLICATION_JSON));
        expectAssets("keyA", 5248);

        SteamGridDbAssets assets = client.fetchByName("dota 2").orElseThrow();

        assertThat(assets.gameId()).isEqualTo(5248);
        assertThat(assets.name()).isEqualTo("Dota 2");
        server.verify();
    }

    @Test
    void byGameId_usesGivenId() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        expectAssets("keyA", 5248);

        assertThat(client.fetchByGameId(5248, "Dota 2")).isPresent();
        server.verify();
    }

    @Test
    void notFound_empty() {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(BASE + "/games/steam/1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"errors\":[\"Game not found\"]}"));

        assertThat(client.fetchBySteamAppId("1")).isEmpty();
    }

    @Test
    void tooManyRequests_blocksKeyAndRetriesWithNext() throws IOException {
        server.expect(once(), requestTo(BASE + "/games/steam/570"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer keyA"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "30"));
        server.expect(once(), requestTo(BASE + "/games/steam/570"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer keyB"))
                .andRespond(withSuccess(fixture("game-steam"), MediaType.APPLICATION_JSON));
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"), Optional.of("keyB"), Optional.of("keyB"),
                Optional.of("keyB"), Optional.of("keyB"), Optional.of("keyB"));
        for (String kind : new String[]{"grids", "heroes", "logos", "icons"}) {
            server.expect(once(), requestTo(BASE + "/" + kind + "/game/5248"))
                    .andRespond(withSuccess(fixture(kind), MediaType.APPLICATION_JSON));
        }

        assertThat(client.fetchBySteamAppId("570")).isPresent();

        verify(rotator).block("keyA", Duration.ofSeconds(30));
        server.verify();
    }

    @Test
    void tooManyRequests_withoutRetryAfter_blocksSixtySeconds() {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"), Optional.of("keyB"));
        server.expect(once(), requestTo(BASE + "/games/steam/570"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo(BASE + "/games/steam/570"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.fetchBySteamAppId("570")).isEmpty();

        verify(rotator).block("keyA", Duration.ofSeconds(60));
    }

    @Test
    void emptyPool_throws_noHttpCall() {
        when(rotator.nextKey()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> client.fetchBySteamAppId("570")).isInstanceOf(SteamGridDbUnavailableException.class);
        assertThatThrownBy(() -> client.fetchByName("dota")).isInstanceOf(SteamGridDbUnavailableException.class);

        server.verify();
        verify(rotator, never()).block(any(), any());
    }

    @Test
    void singleKey429_retryFindsNoKey_throws() {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"), Optional.empty());
        server.expect(once(), requestTo(BASE + "/games/steam/570"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.fetchBySteamAppId("570")).isInstanceOf(SteamGridDbUnavailableException.class);

        verify(rotator).block("keyA", Duration.ofSeconds(60));
        server.verify();
    }

    @Test
    void noRotatorBean_throws_noHttpCall() {
        HttpSteamGridDbClient disabled = new HttpSteamGridDbClient(RestClient.builder(), Optional.empty());

        assertThatThrownBy(() -> disabled.fetchBySteamAppId("570")).isInstanceOf(SteamGridDbUnavailableException.class);
    }

    @Test
    void gridsNotFound_emptyGridsOtherKindsKept() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(BASE + "/grids/game/5248")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        for (String kind : new String[]{"heroes", "logos", "icons"}) {
            server.expect(once(), requestTo(BASE + "/" + kind + "/game/5248"))
                    .andRespond(withSuccess(fixture(kind), MediaType.APPLICATION_JSON));
        }

        SteamGridDbAssets assets = client.fetchByGameId(5248, "Dota 2").orElseThrow();

        assertThat(assets.grids()).isEmpty();
        assertThat(assets.bestHero()).isNotNull();
        server.verify();
    }

    @Test
    void response_neverContainsApiKey() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("super-secret-key"));
        server.expect(once(), requestTo(BASE + "/games/steam/570"))
                .andRespond(withSuccess(fixture("game-steam"), MediaType.APPLICATION_JSON));
        expectAssets("super-secret-key", 5248);

        SteamGridDbAssets assets = client.fetchBySteamAppId("570").orElseThrow();

        assertThat(JsonMapper.builder().build().writeValueAsString(assets)).doesNotContain("super-secret-key");
    }
}
