package fr.enimaloc.catapult.service.enrichment.protondb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProtonDbClientTest {

    private static final String URL = "https://www.protondb.com/api/v1/reports/summaries/570.json";

    private MockRestServiceServer server;
    private HttpProtonDbClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpProtonDbClient(builder);
    }

    @Test
    void summary_parsesFixture() throws IOException {
        String json = new ClassPathResource("enrichment/protondb-570.json").getContentAsString(StandardCharsets.UTF_8);
        server.expect(requestTo(URL)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        ProtonDbSummary summary = client.fetchSummary("570").orElseThrow();

        assertThat(summary.tier()).isEqualTo("platinum");
        assertThat(summary.trendingTier()).isEqualTo("platinum");
        assertThat(summary.bestReportedTier()).isEqualTo("platinum");
        assertThat(summary.confidence()).isEqualTo("strong");
        assertThat(summary.score()).isEqualTo(0.86);
        assertThat(summary.total()).isEqualTo(1234);
        assertThat(summary.url()).isEqualTo("https://www.protondb.com/app/570");
        server.verify();
    }

    @Test
    void notFound_empty() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.fetchSummary("570")).isEmpty();
    }

    @Test
    void protondb_malformedBody_empty() {
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>oops</html>", MediaType.TEXT_HTML));

        assertThat(client.fetchSummary("570")).isEmpty();
    }
}
