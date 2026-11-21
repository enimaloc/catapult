package fr.enimaloc.catapult.service.enrichment.steamdeck;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SteamDeckClientTest {

    private static final String URL =
            "https://store.steampowered.com/saleaction/ajaxgetdeckappcompatibilityreport?nAppID=";

    private MockRestServiceServer server;
    private HttpSteamDeckClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpSteamDeckClient(builder);
    }

    @Test
    void verifiedGame_parsesCategoryAndItems() throws IOException {
        String json = new ClassPathResource("enrichment/steamdeck-570.json").getContentAsString(StandardCharsets.UTF_8);
        server.expect(requestTo(URL + "570")).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        SteamDeckCompatibility result = client.fetch("570").orElseThrow();

        assertThat(result.category()).isEqualTo(SteamDeckCompatibility.Category.VERIFIED);
        assertThat(result.items()).isNotEmpty();
        assertThat(result.items().getFirst().result()).isEqualTo("4");
        assertThat(result.items().getFirst().token())
                .isEqualTo("#SteamDeckVerified_TestResult_DefaultControllerConfigFullySupported");
    }

    @Test
    void unknownCategoryCode_mapsToUnknown() {
        server.expect(requestTo(URL + "1")).andRespond(withSuccess(
                "{\"success\":1,\"results\":{\"appid\":1,\"resolved_category\":9,\"resolved_items\":[]}}",
                MediaType.APPLICATION_JSON));

        Optional<SteamDeckCompatibility> result = client.fetch("1");

        assertThat(result).get().extracting(SteamDeckCompatibility::category)
                .isEqualTo(SteamDeckCompatibility.Category.UNKNOWN);
    }

    @Test
    void steamDeck_malformedBody_empty() {
        server.expect(requestTo(URL + "2")).andRespond(withSuccess("<html>nope</html>", MediaType.TEXT_HTML));

        assertThat(client.fetch("2")).isEmpty();
    }
}
