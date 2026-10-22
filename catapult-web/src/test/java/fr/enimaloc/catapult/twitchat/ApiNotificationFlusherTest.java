package fr.enimaloc.catapult.twitchat;

import fr.enimaloc.catapult.service.http.ApiClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiNotificationFlusherTest {

    private MockWebServer server;
    private ApiNotificationFlusher flusher;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        flusher = new ApiNotificationFlusher(new ApiClient(server.url("/").toString(), RestClient.builder()));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void page_flushesWithTheOwnersJwt() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"delivered\":2}"));

        assertThat(flusher.flushPage("owner.jwt.sig")).isEqualTo(200);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/me/twitchat/notifications/flush");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer owner.jwt.sig");
    }

    @Test
    void widget_flushesWithItsTokenAlone() throws InterruptedException {
        UUID token = UUID.randomUUID();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"delivered\":0}"));

        assertThat(flusher.flushWidget(token)).isEqualTo(200);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/twitchat/widget/" + token + "/notifications/flush");
        assertThat(request.getHeader("Authorization")).isNull();
    }

    @Test
    void anError_isReportedAsItsStatus() {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThat(flusher.flushPage("expired.jwt.sig")).isEqualTo(401);
        assertThat(flusher.flushWidget(UUID.randomUUID())).isEqualTo(404);
    }
}
