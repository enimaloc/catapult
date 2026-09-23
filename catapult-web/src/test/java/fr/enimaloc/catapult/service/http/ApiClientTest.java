package fr.enimaloc.catapult.service.http;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiClientTest {

    private MockWebServer server;
    private ApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new ApiClient(server.url("/").toString(), RestClient.builder());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void postVoid_noBody_noUriVars() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));

        client.postVoid("/api/settings", null);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/api/settings");
        assertThat(recorded.getBody().readUtf8()).isEmpty();
    }

    @Test
    void postVoid_noBody_withOneUriVar() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));

        client.postVoid("/api/channels/{username}/settings/bot", null, "enimaloc");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/api/channels/enimaloc/settings/bot");
        assertThat(recorded.getBody().readUtf8()).isEmpty();
    }

    @Test
    void postVoid_withBody_noUriVars() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));

        record ConfigBody(String setting) {}
        client.postVoid("/api/settings", new ConfigBody("value"));

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/settings");
        assertThat(recorded.getBody().readUtf8()).contains("\"setting\":\"value\"");
    }

    @Test
    void postVoid_withBody_withOneUriVar() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));

        record ToggleBody(boolean enabled) {}
        client.postVoid("/api/channels/{username}/settings/toggle",
                new ToggleBody(true), "enimaloc");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/channels/enimaloc/settings/toggle");
        assertThat(recorded.getBody().readUtf8()).contains("\"enabled\":true");
    }

    @Test
    void postVoid_withBody_withMultipleUriVars() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));

        record ToggleBody(boolean enabled) {}
        client.postVoid("/api/channels/{username}/bindings/{id}/ccl-toggle",
                new ToggleBody(true), "enimaloc", "abc-123");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/channels/enimaloc/bindings/abc-123/ccl-toggle");
        assertThat(recorded.getBody().readUtf8()).contains("\"enabled\":true");
    }
}
