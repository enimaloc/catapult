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

    /** Grids are asked with the portrait cover sizes; the other kinds as they are. */
    private static String assetUrl(String kind, long id) {
        return BASE + "/" + kind + "/game/" + id
                + ("grids".equals(kind) ? "?dimensions=600x900,342x482,660x930" : "");
    }

    private void expectAssets(String key, long id) throws IOException {
        for (String kind : new String[]{"grids", "heroes", "logos", "icons"}) {
            server.expect(once(), requestTo(assetUrl(kind, id)))
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
    void byGameId_withoutName_readsTheGameFirst() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(BASE + "/games/id/5248"))
                .andRespond(withSuccess(fixture("game-steam"), MediaType.APPLICATION_JSON));
        expectAssets("keyA", 5248);

        SteamGridDbAssets assets = client.fetchByGameId(5248, null).orElseThrow();

        assertThat(assets.gameId()).isEqualTo(5248);
        assertThat(assets.name()).isEqualTo("Dota 2");
        server.verify();
    }

    @Test
    void byGameId_unknownId_empty_noAssetCall() {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(BASE + "/games/id/1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"errors\":[\"Game not found\"]}"));

        assertThat(client.fetchByGameId(1, null)).isEmpty();
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
            server.expect(once(), requestTo(assetUrl(kind, 5248)))
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
    void isConfigured_onlyWithKeysInThePool() {
        when(rotator.hasKeys()).thenReturn(false, true);

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.isConfigured()).isTrue();
        assertThat(new HttpSteamGridDbClient(RestClient.builder(), Optional.empty()).isConfigured()).isFalse();
    }

    @Test
    void noRotatorBean_throws_noHttpCall() {
        HttpSteamGridDbClient disabled = new HttpSteamGridDbClient(RestClient.builder(), Optional.empty());

        assertThatThrownBy(() -> disabled.fetchBySteamAppId("570")).isInstanceOf(SteamGridDbUnavailableException.class);
    }

    @Test
    void gridsNotFound_emptyGridsOtherKindsKept() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(assetUrl("grids", 5248))).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(BASE + "/grids/game/5248")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        for (String kind : new String[]{"heroes", "logos", "icons"}) {
            server.expect(once(), requestTo(assetUrl(kind, 5248)))
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

    private void expectSearch(String encodedTerm, String body) {
        server.expect(once(), requestTo(BASE + "/search/autocomplete/" + encodedTerm))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String hits(String... idAndName) {
        StringBuilder out = new StringBuilder("{\"success\":true,\"data\":[");
        for (int i = 0; i < idAndName.length; i += 2) {
            out.append(i == 0 ? "" : ",").append("{\"id\":").append(idAndName[i]).append(",\"name\":\"")
                    .append(idAndName[i + 1]).append("\"}");
        }
        return out.append("]}").toString();
    }

    @Test
    void byName_searchesTheFullCleanedName_andPicksTheNameWithoutItsEditionOverTheFirstResult() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        expectSearch("the%20witcher%203%3A%20wild%20hunt%20-%20game%20of%20the%20year%20edition",
                hits("100", "The Witcher 3: Wild Hunt - Blood and Wine", "5248", "The Witcher 3: Wild Hunt\u2122"));
        expectAssets("keyA", 5248);

        SteamGridDbAssets assets = client.fetchByName("The Witcher 3: Wild Hunt - Game of the Year Edition")
                .orElseThrow();

        assertThat(assets.gameId()).isEqualTo(5248);
        server.verify();
    }

    @Test
    void byName_aRemakeIsNotFoldedIntoTheOriginal_whateverTheOrder() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        expectSearch("mafia%3A%20definitive%20edition", hits("1", "Mafia", "5248", "Mafia: Definitive Edition"));
        expectAssets("keyA", 5248);
        assertThat(client.fetchByName("Mafia: Definitive Edition").orElseThrow().gameId()).isEqualTo(5248);
        server.verify();
        server.reset();
        expectSearch("mafia%3A%20definitive%20edition", hits("5248", "Mafia: Definitive Edition", "1", "Mafia"));
        expectAssets("keyA", 5248);
        assertThat(client.fetchByName("Mafia: Definitive Edition").orElseThrow().gameId()).isEqualTo(5248);
        server.verify();
    }

    @Test
    void byName_theOriginalStillMatchesItself() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        expectSearch("mafia", hits("5248", "Mafia: Definitive Edition", "1", "Mafia"));
        expectAssets("keyA", 1);

        assertThat(client.fetchByName("Mafia").orElseThrow().gameId()).isEqualTo(1);
        server.verify();
    }

    @Test
    void byName_nothingForTheFullName_searchesWithoutTheEdition() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        expectSearch("hades%20-%20ultimate%20edition", "{\"success\":true,\"data\":[]}");
        expectSearch("hades", hits("5248", "Hades"));
        expectAssets("keyA", 5248);

        assertThat(client.fetchByName("Hades - Ultimate Edition").orElseThrow().gameId()).isEqualTo(5248);
        server.verify();
    }

    @Test
    void byName_noExactMatch_fallsBackToTheFirstResult() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        expectSearch("hades", hits("5248", "Hades II", "9", "Hades Star"));
        expectAssets("keyA", 5248);

        assertThat(client.fetchByName("Hades").orElseThrow().gameId()).isEqualTo(5248);
        server.verify();
    }

    @Test
    void byName_ignoresPunctuationAndAccentsWhenMatching() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        expectSearch("pok%C3%A9mon%20red", hits("1", "Pokemon Red Version Remake", "5248", "Pok\u00E9mon: Red"));
        expectAssets("keyA", 5248);

        assertThat(client.fetchByName("Pok\u00E9mon Red").orElseThrow().gameId()).isEqualTo(5248);
        server.verify();
    }

    @Test
    void byName_blankAfterNormalisation_doesNotSearch() {
        assertThat(client.fetchByName("\u2122")).isEmpty();
        server.verify();
    }

    @Test
    void grids_noPortraitSize_fallsBackToEveryGrid() throws IOException {
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        server.expect(once(), requestTo(assetUrl("grids", 5248)))
                .andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/grids/game/5248"))
                .andRespond(withSuccess(fixture("grids"), MediaType.APPLICATION_JSON));
        for (String kind : new String[]{"heroes", "logos", "icons"}) {
            server.expect(once(), requestTo(assetUrl(kind, 5248)))
                    .andRespond(withSuccess(fixture(kind), MediaType.APPLICATION_JSON));
        }

        assertThat(client.fetchByGameId(5248, "Dota 2").orElseThrow().grids()).hasSize(2);
        server.verify();
    }
}
