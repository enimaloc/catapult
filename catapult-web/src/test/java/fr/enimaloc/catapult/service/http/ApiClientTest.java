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
    void postVoid_sendsJsonBody() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));

        client.postVoid("/api/channels/{username}/settings/bot", "enimaloc");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/api/channels/enimaloc/settings/bot");
    }

    @Test
    void postVoid_withBody_sendsSerializedJson() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));

        record ToggleBody(boolean enabled) {}
        client.postVoid("/api/channels/{username}/bindings/{id}/ccl-toggle",
                new ToggleBody(true), "enimaloc", "abc-123");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/channels/enimaloc/bindings/abc-123/ccl-toggle");
        assertThat(recorded.getBody().readUtf8()).contains("\"enabled\":true");
    }
}
