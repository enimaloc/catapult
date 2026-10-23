package fr.enimaloc.catapult.service.degraded;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class InstatusClientTest {
    private static final String PAGE = "https://s.example";

    private MockRestServiceServer server;
    private InstatusClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new InstatusClient(builder.build());
    }

    private void answer(String pageUrl, String body) {
        server.expect(requestTo(pageUrl + "/summary.json")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void severalIncidents_givesTheMostRecentlyStarted() {
        answer(PAGE, """
                {"activeIncidents": [
                  {"url": "https://s.example/incident/A", "started": "2026-10-23T10:00:00.000Z"},
                  {"url": "https://s.example/incident/B", "started": "2026-10-23T11:00:00.000Z"}]}""");

        assertThat(client.latestIncidentUrl(PAGE)).contains("https://s.example/incident/B");
    }

    @Test
    void noIncident_givesNothing() {
        answer(PAGE, "{\"activeIncidents\": []}");

        assertThat(client.latestIncidentUrl(PAGE)).isEmpty();
    }

    @Test
    void serverError_givesNothing() {
        server.expect(requestTo(PAGE + "/summary.json")).andRespond(withServerError());

        assertThat(client.latestIncidentUrl(PAGE)).isEmpty();
    }

    @Test
    void malformedBody_givesNothing() {
        answer(PAGE, "not json");

        assertThat(client.latestIncidentUrl(PAGE)).isEmpty();
    }

    @Test
    void timeout_givesNothing() {
        server.expect(requestTo(PAGE + "/summary.json")).andRespond(withException(new SocketTimeoutException()));

        assertThat(client.latestIncidentUrl(PAGE)).isEmpty();
    }

    @Test
    void nonHttpUrl_isIgnored() {
        answer(PAGE, """
                {"activeIncidents": [{"url": "javascript:alert(1)", "started": "2026-10-23T10:00:00.000Z"}]}""");

        assertThat(client.latestIncidentUrl(PAGE)).isEmpty();
    }

    @Test
    void incidentWithoutUrlOrDate_isSkipped() {
        answer(PAGE, """
                {"activeIncidents": [
                  {"started": "2026-10-23T12:00:00.000Z"},
                  {"url": "https://s.example/incident/A", "started": "yesterday"},
                  {"url": "https://s.example/incident/B", "started": "2026-10-23T10:00:00.000Z"}]}""");

        assertThat(client.latestIncidentUrl(PAGE)).contains("https://s.example/incident/B");
    }

    @Test
    void trailingSlashInPageUrl_isTolerated() {
        answer(PAGE, "{\"activeIncidents\": [{\"url\": \"https://s.example/incident/A\", \"started\": \"2026-10-23T10:00:00Z\"}]}");

        Optional<String> url = client.latestIncidentUrl(PAGE + "/");

        assertThat(url).contains("https://s.example/incident/A");
        server.verify();
    }
}
