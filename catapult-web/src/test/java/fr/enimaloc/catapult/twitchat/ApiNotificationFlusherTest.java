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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    void flushPage_postsToTheTokensOwner() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"delivered\":2}"));

        UUID owner = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        String jwt = "header." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + owner + "\"}").getBytes(StandardCharsets.UTF_8)) + ".sig";

        assertThat(flusher.flushPage(jwt)).isEqualTo(200);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/internal/users/" + owner + "/twitchat-notifications/flush");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + jwt);
    }

    @Test
    void flushPage_withoutAReadableTokenAnswers401WithoutCalling() {
        assertThat(flusher.flushPage("not-a-jwt")).isEqualTo(401);
        assertThat(flusher.flushPage(null)).isEqualTo(401);

        assertThat(server.getRequestCount()).isZero();
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
        String jwt = "header." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8)) + ".sig";

        assertThat(flusher.flushPage(jwt)).isEqualTo(401);
        assertThat(flusher.flushWidget(UUID.randomUUID())).isEqualTo(404);
    }
}
