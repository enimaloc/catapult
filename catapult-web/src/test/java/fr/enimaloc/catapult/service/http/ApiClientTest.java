package fr.enimaloc.catapult.service.http;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

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
        RequestContextHolder.resetRequestAttributes();
        server.shutdown();
    }

    record Payload(String name) {}

    private static void inSessionWithJwt(String jwt) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ApiClient.SESSION_JWT_KEY, jwt);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
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

    @Test
    void get_readsTheBodyAsTheRequestedType() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"name\":\"x\"}"));

        Payload payload = client.get("/api/things/{id}", Payload.class, "42");

        assertThat(payload).isEqualTo(new Payload("x"));
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getPath()).isEqualTo("/api/things/42");
    }

    @Test
    void post_sendsNoBodyAndReadsTheResponse() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"name\":\"tok\"}"));

        Payload payload = client.post("/api/auth/exchange?code={code}", Payload.class, "abc");

        assertThat(payload).isEqualTo(new Payload("tok"));
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/api/auth/exchange?code=abc");
    }

    @Test
    void delete_sendsADeleteRequest() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));

        client.delete("/api/connect/{provider}", "minecraft");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("DELETE");
        assertThat(recorded.getPath()).isEqualTo("/api/connect/minecraft");
    }

    @Test
    void forwardsTheSessionJwtAsBearerToken() throws InterruptedException {
        inSessionWithJwt("the-jwt");
        server.enqueue(new MockResponse().setResponseCode(204));

        client.postVoid("/api/x", null);

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer the-jwt");
    }

    @Test
    void sendsNoAuthorizationWithoutSessionOrJwt() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(204));
        client.postVoid("/api/x", null);
        assertThat(server.takeRequest().getHeader("Authorization")).isNull();

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        server.enqueue(new MockResponse().setResponseCode(204));
        client.postVoid("/api/x", null);
        assertThat(server.takeRequest().getHeader("Authorization")).isNull();
    }

    @Test
    void failedCalls_returnNullInsteadOfThrowing() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse().setResponseCode(401));

        assertThat(client.get("/api/a", Payload.class)).isNull();
        assertThat(client.post("/api/b", Payload.class)).isNull();
        assertThat(client.get("/api/auth/validate", Payload.class)).isNull();
    }

    @Test
    void failedVoidCalls_areSwallowed() throws IOException {
        server.enqueue(new MockResponse().setResponseCode(503));
        client.postVoid("/api/a", new Payload("x"));

        server.shutdown();
        client.delete("/api/unreachable");
    }

    @Test
    void unreachableBackend_returnsNull() throws IOException {
        server.shutdown();

        assertThat(client.get("/api/a", Payload.class)).isNull();
    }

    @Test
    void slowResponse_isStillRead() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"late\"}").setBodyDelay(50, TimeUnit.MILLISECONDS));

        assertThat(client.get("/api/a", Payload.class)).isEqualTo(new Payload("late"));
    }
}
