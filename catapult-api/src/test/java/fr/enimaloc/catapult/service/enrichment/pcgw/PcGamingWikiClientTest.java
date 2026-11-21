package fr.enimaloc.catapult.service.enrichment.pcgw;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PcGamingWikiClientTest {

    private static final String UA = "Catapult/1.0 (+https://catapult.test)";

    private MockRestServiceServer server;
    private HttpPcGamingWikiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpPcGamingWikiClient(builder, JsonMapper.builder().build(), "https://catapult.test");
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("enrichment/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    /** Matches a query parameter after URL-decoding, so the assertions read like the MediaWiki query itself. */
    private static RequestMatcher decodedParam(String name, String expected) {
        return request -> assertThat(UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams()
                .getFirst(name)).isNotNull().satisfies(raw -> assertThat(UriUtils.decode(raw, StandardCharsets.UTF_8))
                .isEqualTo(expected));
    }

    private void expectSearch(String query, String body) {
        server.expect(r -> { })
                .andExpect(decodedParam("list", "search"))
                .andExpect(decodedParam("srsearch", query))
                .andExpect(header("User-Agent", UA))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectParse(long pageId, String body) {
        server.expect(r -> { })
                .andExpect(decodedParam("action", "parse"))
                .andExpect(decodedParam("pageid", String.valueOf(pageId)))
                .andExpect(header("User-Agent", UA))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void bySteamAppId_findsPageThenFields() throws IOException {
        expectSearch("\"steam appid  = 570\"", fixture("pcgw-search.json"));
        expectParse(900, fixture("pcgw-parse-sidekey.json"));
        expectParse(195, fixture("pcgw-parse-dota2.json"));

        PcGamingWikiPage page = client.findBySteamAppId("570").orElseThrow();

        assertThat(page.pageId()).isEqualTo(195);
        assertThat(page.title()).isEqualTo("Dota 2");
        assertThat(page.url()).isEqualTo("https://www.pcgamingwiki.com/wiki/Dota_2");
        assertThat(page.ultrawide()).isEqualTo("true");
        assertThat(page.hdr()).isEqualTo("unknown");
        assertThat(page.controller()).isEqualTo("false");
        assertThat(page.saveLocations()).isEmpty();
        server.verify();
    }

    @Test
    void appIdSideKey_doesNotMatch() throws IOException {
        expectSearch("\"steam appid  = 570\"",
                "{\"query\":{\"search\":[{\"title\":\"Dota 2 Workshop Tools\",\"pageid\":900}]}}");
        expectParse(900, fixture("pcgw-parse-sidekey.json"));

        assertThat(client.findBySteamAppId("570")).isEmpty();
        server.verify();
    }

    @Test
    void byName_exactPageNameMatch() throws IOException {
        expectSearch("dota  2", fixture("pcgw-search.json"));
        expectParse(195, fixture("pcgw-parse-dota2.json"));

        assertThat(client.findByName("dota  2")).get().extracting(PcGamingWikiPage::pageId).isEqualTo(195L);
        server.verify();
    }

    @Test
    void byName_noExactTitle_empty() throws IOException {
        expectSearch("Dota", fixture("pcgw-search.json"));

        assertThat(client.findByName("Dota")).isEmpty();
        server.verify();
    }

    @Test
    void byPageId_readsTitleAndFields() throws IOException {
        expectParse(195, fixture("pcgw-parse-dota2.json"));

        assertThat(client.findByPageId(195)).get().extracting(PcGamingWikiPage::title).isEqualTo("Dota 2");
        server.verify();
    }

    @Test
    void noRow_empty() {
        expectSearch("\"steam appid  = 1\"", "{\"query\":{\"search\":[]}}");
        expectParse(7, "{\"error\":{\"code\":\"nosuchpageid\",\"info\":\"gone\"}}");

        assertThat(client.findBySteamAppId("1")).isEmpty();
        assertThat(client.findByPageId(7)).isEmpty();
        server.verify();
    }

    @Test
    void pcgw_malformedBody_empty() {
        expectSearch("\"steam appid  = 2\"", "<html>nope</html>");
        expectParse(8, "<html>nope</html>");

        assertThat(client.findBySteamAppId("2")).isEmpty();
        assertThat(client.findByPageId(8)).isEmpty();
        server.verify();
    }

    @Test
    void permissionDeniedAndServerErrors_propagate() {
        expectSearch("\"steam appid  = 3\"",
                "{\"error\":{\"code\":\"permissiondenied\",\"info\":\"no\"}}");
        server.expect(r -> { }).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.findBySteamAppId("3")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.findByPageId(9)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void uaHeaderSent() throws IOException {
        expectParse(195, fixture("pcgw-parse-dota2.json"));

        client.findByPageId(195);

        server.verify(); // expectParse asserts the User-Agent header
    }
}
