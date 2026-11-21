package fr.enimaloc.catapult.service.enrichment.pcgw;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PcGamingWikiClientTest {

    private MockRestServiceServer server;
    private HttpPcGamingWikiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpPcGamingWikiClient(builder, JsonMapper.builder().build());
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("enrichment/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    /** Matches a query parameter after URL-decoding, so the assertions read like the Cargo query itself. */
    private static RequestMatcher decodedParam(String name, String expected) {
        return request -> assertThat(UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams()
                .getFirst(name)).isNotNull().satisfies(raw -> assertThat(UriUtils.decode(raw, StandardCharsets.UTF_8))
                .isEqualTo(expected));
    }

    private void expectCargo(String table, String where, String body) {
        server.expect(r -> { })
                .andExpect(decodedParam("action", "cargoquery"))
                .andExpect(decodedParam("format", "json"))
                .andExpect(decodedParam("tables", table))
                .andExpect(decodedParam("where", where))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectFields(long pageId) throws IOException {
        expectCargo("Video", "Video._pageID=" + pageId, fixture("pcgw-page-fields.json"));
        expectCargo("Input", "Input._pageID=" + pageId, fixture("pcgw-page-input.json"));
    }

    @Test
    void bySteamAppId_findsPageThenFields() throws IOException {
        expectCargo("Infobox_game", "Infobox_game.Steam_AppID HOLDS \"570\"", fixture("pcgw-by-appid.json"));
        expectFields(1234);

        PcGamingWikiPage page = client.findBySteamAppId("570").orElseThrow();

        assertThat(page.pageId()).isEqualTo(1234);
        assertThat(page.title()).isEqualTo("Dota 2");
        assertThat(page.url()).isEqualTo("https://www.pcgamingwiki.com/wiki/Dota_2");
        assertThat(page.ultrawide()).isEqualTo("hackable");
        assertThat(page.hdr()).isEqualTo("false");
        assertThat(page.controller()).isEqualTo("true");
        assertThat(page.saveLocations()).isEmpty();
        server.verify();
    }

    @Test
    void byName_exactPageNameMatch() throws IOException {
        expectCargo("Infobox_game", "Infobox_game._pageName=\"Dota 2\"", fixture("pcgw-by-name.json"));
        expectFields(1234);

        assertThat(client.findByName("Dota 2")).get().extracting(PcGamingWikiPage::pageId).isEqualTo(1234L);
        server.verify();
    }

    @Test
    void byPageId_readsTitleThenFields() throws IOException {
        expectCargo("Infobox_game", "Infobox_game._pageID=1234", fixture("pcgw-by-appid.json"));
        expectFields(1234);

        assertThat(client.findByPageId(1234)).get().extracting(PcGamingWikiPage::title).isEqualTo("Dota 2");
        server.verify();
    }

    @Test
    void noRow_empty() {
        expectCargo("Infobox_game", "Infobox_game.Steam_AppID HOLDS \"1\"", "{\"cargoquery\":[]}");

        assertThat(client.findBySteamAppId("1")).isEmpty();
        server.verify();
    }

    @Test
    void pcgw_malformedBody_empty() {
        expectCargo("Infobox_game", "Infobox_game.Steam_AppID HOLDS \"2\"", "<html>nope</html>");
        expectCargo("Infobox_game", "Infobox_game.Steam_AppID HOLDS \"3\"", "{\"error\":{\"code\":\"x\"}}");

        assertThat(client.findBySteamAppId("2")).isEmpty();
        assertThat(client.findBySteamAppId("3")).isEmpty();
        server.verify();
    }
}
