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

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import fr.enimaloc.catapult.common.error.ErrorCode;

import java.io.IOException;
import java.util.List;
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
    void anErrorAnswersBody_isNeverReadAsTheExpectedOne() {
        // A ProblemDetail or a Spring error body whose fields happen to fit the expected type.
        server.enqueue(new MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"Not Found\"}"));
        server.enqueue(new MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("[{\"name\":\"bad\"}]"));

        assertThat(client.get("/api/a", Payload.class)).isNull();
        assertThat(client.get("/api/a", new ParameterizedTypeReference<List<Payload>>() {})).isNull();
    }

    @Test
    void actions_answerCatapultApisStatus_or502WhenUnreachable() throws IOException {
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"message\":\"no\"}"));
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThat(client.postVoid("/api/a", new Payload("x"))).isEqualTo(204);
        assertThat(client.postVoid("/api/a", null)).isEqualTo(403);
        assertThat(client.delete("/api/a")).isEqualTo(503);

        server.shutdown();
        assertThat(client.delete("/api/unreachable")).isEqualTo(502);
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

    @Test
    void call_keepsThe4xxStatusAndBody() {
        server.enqueue(new MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"bad\"}"));

        ApiClient.Result<Payload> result = client.call(org.springframework.http.HttpMethod.PUT, "/api/a", new Payload("x"), Payload.class);

        assertThat(result).isEqualTo(new ApiClient.Result<>(400, new Payload("bad")));
        assertThat(result.ok()).isFalse();
    }

    @Test
    void call_noContent_hasANullBody() {
        server.enqueue(new MockResponse().setResponseCode(204));

        assertThat(client.call(org.springframework.http.HttpMethod.POST, "/api/a", null, Payload.class))
                .isEqualTo(new ApiClient.Result<Payload>(204, null));
    }

    @Test
    void call_unreachableBackend_is502() throws IOException {
        server.shutdown();

        assertThat(client.call(org.springframework.http.HttpMethod.POST, "/api/a", null, Payload.class).status()).isEqualTo(502);
    }

    private static String jwtWithPayload(String json) {
        return "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes()) + ".sig";
    }

    @Test
    void currentSubject_readsTheJwtSubject() {
        java.util.UUID id = java.util.UUID.randomUUID();
        inSessionWithJwt(jwtWithPayload("{\"sub\":\"" + id + "\"}"));

        assertThat(ApiClient.currentSubject()).isEqualTo(id);
    }

    @Test
    void currentSubject_isNullWithoutASessionOrWithAMalformedJwt() {
        assertThat(ApiClient.currentSubject()).isNull();

        inSessionWithJwt("not-a-jwt");
        assertThat(ApiClient.currentSubject()).isNull();

        inSessionWithJwt(jwtWithPayload("{\"sub\":\"nope\"}"));
        assertThat(ApiClient.currentSubject()).isNull();
    }

    @Test
    void currentRoles_readsTheJwtRoles_noneWithoutThem() {
        assertThat(ApiClient.currentRoles()).isEmpty();

        inSessionWithJwt(jwtWithPayload("{\"sub\":\"x\"}"));
        assertThat(ApiClient.currentRoles()).isEmpty();

        inSessionWithJwt(jwtWithPayload("{\"roles\":[\"ROLE_USER\",\"ROLE_ADMIN\"]}"));
        assertThat(ApiClient.currentRoles()).containsExactly("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void currentLogin_readsTheJwtUsername_noneWithoutIt() {
        assertThat(ApiClient.currentLogin()).isNull();

        inSessionWithJwt(jwtWithPayload("{\"sub\":\"x\"}"));
        assertThat(ApiClient.currentLogin()).isNull();

        inSessionWithJwt(jwtWithPayload("{\"sub\":\"x\",\"username\":\"alice\"}"));
        assertThat(ApiClient.currentLogin()).isEqualTo("alice");
    }

    @Test
    void getAnonymous_goesOutWithoutTheSessionsBearer_norItsOwnMarker() throws InterruptedException {
        inSessionWithJwt("expired.jwt.sig");
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"name\":\"public\"}"));

        assertThat(client.getAnonymous("/api/public/{id}", Payload.class, "p1"))
                .isEqualTo(new ApiClient.Result<>(200, new Payload("public")));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/public/p1");
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getHeader("X-Catapult-Anonymous")).isNull();
    }

    @Test
    void getAnonymous_keepsTheStatus_withoutABodyItCannotRead_or502WhenUnreachable() throws IOException {
        server.enqueue(new MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"Not Found\"}"));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{oops"));

        assertThat(client.getAnonymous("/api/public", Payload.class)).isEqualTo(new ApiClient.Result<Payload>(404, null));
        assertThat(client.getAnonymous("/api/public", Payload.class)).isEqualTo(new ApiClient.Result<Payload>(200, null));

        server.shutdown();
        assertThat(client.getAnonymous("/api/public", Payload.class)).isEqualTo(new ApiClient.Result<Payload>(502, null));
    }

    @Test
    void callAs_bringsItsOwnBearer_overTheCallingSessions() throws InterruptedException {
        inSessionWithJwt("session.jwt.sig");
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"name\":\"x\"}"));

        assertThat(client.callAs(org.springframework.http.HttpMethod.POST, "/api/flush/{id}", "kept.jwt.sig", Payload.class, "a"))
                .isEqualTo(new ApiClient.Result<>(200, new Payload("x")));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/flush/a");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer kept.jwt.sig");
    }

    @Test
    void callAs_withoutAJwt_isAnonymous_andKeepsAnErrorStatus() throws InterruptedException {
        inSessionWithJwt("session.jwt.sig");
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThat(client.callAs(org.springframework.http.HttpMethod.POST, "/api/flush", null, Payload.class))
                .isEqualTo(new ApiClient.Result<Payload>(404, null));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getHeader("X-Catapult-Anonymous")).isNull();
    }

    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @Test
    void aFailureOnAWidgetRoute_neverLogsTheWidgetToken(org.springframework.boot.test.system.CapturedOutput output) {
        java.util.UUID token = java.util.UUID.randomUUID();
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse().setResponseCode(500));

        client.callAs(org.springframework.http.HttpMethod.POST, "/api/twitchat/widget/{token}/notifications/flush", null, Payload.class, token);
        client.getAnonymous("/api/twitchat/widget/{token}/access", Payload.class, token);

        assertThat(output.getAll()).contains("API returned 404").doesNotContain(token.toString());
    }

    @Test
    void maskedHidesTheSegmentAfterWidget_inPathsAndMessages() {
        assertThat(ApiClient.masked("/api/twitchat/widget/abc-123/access")).isEqualTo("/api/twitchat/widget/***/access");
        assertThat(ApiClient.masked("I/O error on POST request for \"http://h:1/api/twitchat/widget/abc-123/x\": refused"))
                .doesNotContain("abc-123");
        assertThat(ApiClient.masked(null)).isNull();
    }

    @Test
    void callAs_is502WhenUnreachable() throws IOException {
        server.shutdown();

        assertThat(client.callAs(org.springframework.http.HttpMethod.POST, "/api/flush", "j.w.t", Payload.class).status()).isEqualTo(502);
    }

    @Test
    void theSessionsJwt_isUnreadable_whenItsPayloadIsNotBase64_orItsRequestIsOver() {
        inSessionWithJwt("h.!not base64!.sig");
        assertThat(ApiClient.currentSubject()).isNull();
        assertThat(ApiClient.hasSession()).isTrue();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ApiClient.SESSION_JWT_KEY, jwtWithPayload("{\"username\":\"alice\"}"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(ApiClient.currentLogin()).isEqualTo("alice");
        // Read from an async thread once the request is done.
        request.close();
        assertThat(ApiClient.currentLogin()).isNull();
        assertThat(ApiClient.hasSession()).isFalse();
    }

    @Test
    void call_keepsTheStatus_whenTheBodyIsNotTheRequestedType() {
        server.enqueue(new MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"nope\"}"));

        assertThat(client.call(org.springframework.http.HttpMethod.PUT, "/api/a", null, Payload[].class))
                .isEqualTo(new ApiClient.Result<Payload[]>(403, null));
    }

    private static MockResponse problem(int status, String contentType, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", contentType).setBody(body);
    }

    @Test
    void call_readsTheProblemCode_whateverItsContentType() {
        String body = "{\"code\":\"name_taken\",\"detail\":\"x\"}";
        server.enqueue(problem(409, "application/problem+json", body));
        server.enqueue(problem(409, "application/json", body));

        for (int i = 0; i < 2; i++) {
            ApiClient.Result<Payload> result = client.call(HttpMethod.POST, "/api/a", null, Payload.class);

            assertThat(result.status()).isEqualTo(409);
            assertThat(result.error()).isNotNull();
            assertThat(result.error().code()).isEqualTo(ErrorCode.NAME_TAKEN);
            assertThat(result.error().detail()).isEqualTo("x");
        }
    }

    @Test
    void call_errorWithoutAProblem_hasNoError() {
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(problem(400, "application/json", "[{\"index\":0}]"));

        ApiClient.Result<Payload> empty = client.call(HttpMethod.POST, "/api/a", null, Payload.class);
        ApiClient.Result<TwitchatValidationError[]> list =
                client.call(HttpMethod.POST, "/api/a", null, TwitchatValidationError[].class);

        assertThat(empty).isEqualTo(new ApiClient.Result<Payload>(404, null));
        assertThat(empty.error()).isNull();
        assertThat(list.error()).isNull();
        assertThat(list.body()).containsExactly(new TwitchatValidationError(0, null, null));
    }

    @Test
    void call_unknownCode_keepsTheDetail() {
        server.enqueue(problem(409, "application/problem+json", "{\"code\":\"from_the_future\",\"detail\":\"y\"}"));

        ApiClient.Result<Payload> result = client.call(HttpMethod.POST, "/api/a", null, Payload.class);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().code()).isNull();
        assertThat(result.error().detail()).isEqualTo("y");
    }

    @Test
    void call_readsAnExtension() {
        server.enqueue(problem(502, "application/problem+json",
                "{\"code\":\"minecraft_device_code_refused\",\"upstream\":\"AADSTS70002\"}"));

        ApiClient.Result<Payload> result = client.call(HttpMethod.POST, "/api/a", null, Payload.class);

        assertThat(result.error().code()).isEqualTo(ErrorCode.MINECRAFT_DEVICE_CODE_REFUSED);
        assertThat(result.error().property("upstream", String.class)).isEqualTo("AADSTS70002");
        assertThat(result.error().property("absent", String.class)).isNull();
        assertThat(result.error().detail()).isNull();
    }

    @Test
    void call_success_hasNoError() {
        server.enqueue(problem(200, "application/json", "{\"name\":\"ok\",\"code\":\"name_taken\"}"));

        ApiClient.Result<Payload> result = client.call(HttpMethod.POST, "/api/a", null, Payload.class);

        assertThat(result).isEqualTo(new ApiClient.Result<>(200, new Payload("ok")));
        assertThat(result.error()).isNull();
    }

    @Test
    void parse_givesAnErrorOnlyForAnObjectWithATextualCode() {
        assertThat(ApiError.parse(null)).isNull();
        assertThat(ApiError.parse(new byte[0])).isNull();
        assertThat(ApiError.parse("{oops".getBytes())).isNull();
        assertThat(ApiError.parse("[]".getBytes())).isNull();
        assertThat(ApiError.parse("{\"detail\":\"x\"}".getBytes())).isNull();
        assertThat(ApiError.parse("{\"code\":42}".getBytes())).isNull();
        assertThat(ApiError.parse("{\"code\":\"conflict\",\"detail\":7}".getBytes()))
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(error.detail()).isNull();
                });
    }

    @Test
    void property_isNullWhenItCannotBeReadAsTheType() {
        ApiError error = ApiError.parse("{\"code\":\"conflict\",\"count\":\"many\",\"n\":3}".getBytes());

        assertThat(error.property("count", Integer.class)).isNull();
        assertThat(error.property("n", Integer.class)).isEqualTo(3);
    }

    @Test
    void call_errorWhoseBodyIsCut_keepsTheStatus() {
        server.enqueue(problem(409, "application/problem+json", "{\"code\":\"name_taken\",\"detail\":\"" + "x".repeat(4096) + "\"}")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

        ApiClient.Result<Payload> result = client.call(HttpMethod.POST, "/api/a", null, Payload.class);

        assertThat(result).isEqualTo(new ApiClient.Result<Payload>(409, null));
    }

    @Test
    void call_errorBodyWithAnUnknownField_isStillRead() {
        server.enqueue(problem(400, "application/json", "{\"name\":\"bad\",\"extra\":1}"));
        server.enqueue(problem(400, "application/json", "[{\"index\":0,\"field\":\"f\",\"message\":\"m\",\"extra\":true}]"));

        assertThat(client.call(HttpMethod.PUT, "/api/a", null, Payload.class).body()).isEqualTo(new Payload("bad"));
        assertThat(client.call(HttpMethod.PUT, "/api/a", null, TwitchatValidationError[].class).body())
                .containsExactly(new TwitchatValidationError(0, "f", "m"));
    }

    @Test
    void call_problemBody_readsAsACustomBindingError_keepingItsCode() {
        server.enqueue(problem(409, "application/problem+json", "{\"type\":\"about:blank\",\"title\":\"Conflict\","
                + "\"status\":409,\"detail\":\"Name taken\",\"instance\":\"/api/v2/custom-bindings\",\"code\":\"name_taken\"}"));

        ApiClient.Result<fr.enimaloc.catapult.common.dto.binding.CustomBindingErrorDto> result = client.call(HttpMethod.POST,
                "/api/v2/custom-bindings", null, fr.enimaloc.catapult.common.dto.binding.CustomBindingErrorDto.class);

        assertThat(result.body()).isNotNull();
        assertThat(result.body().code()).isEqualTo("name_taken");
        assertThat(result.error().code()).isEqualTo(ErrorCode.NAME_TAKEN);
    }
}
