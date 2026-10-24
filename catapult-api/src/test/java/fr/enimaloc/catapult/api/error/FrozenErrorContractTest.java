package fr.enimaloc.catapult.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.FullContextTestBase;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The error answers of the frozen routes, byte for byte, on a real server: external callers (OBS browser sources,
 * Twitchat, pasted URLs, catapult-web's relay) depend on them, so the error contract must leave them as they are.
 * A real port is needed because MockMvc never dispatches to Boot's {@code /error}, which writes most of these bodies.
 */
@Import(FullContextContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FrozenErrorContractTest extends FullContextTestBase {

    /** Boot's error page body; {@code timestamp} changes with every answer, so only its shape is pinned. */
    static final String BOOT_ERROR = "^\\{\"timestamp\":\"[^\"]+\",\"status\":%d,\"error\":\"%s\",\"path\":\"%s\"\\}$";

    private static final String UNKNOWN = UUID.randomUUID().toString();

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort
    int port;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    WidgetTokenService widgetTokenService;

    /** An active account with no detected game, by its widget token. */
    UUID token;

    @BeforeAll
    void fixture() {
        UserAccount account = new UserAccount();
        account.setTwitchUsername("frozen-errors");
        account.setTwitchId("frozen-errors-twitch");
        account.setStatus(UserAccount.Status.ACTIVE);
        token = widgetTokenService.getOrCreate(accounts.save(account));
    }

    static Stream<Arguments> emptyNotFound() {
        return Stream.of(
                Arguments.of("GET", "/api/game/" + UNKNOWN),
                Arguments.of("GET", "/api/user/game/" + UNKNOWN),
                Arguments.of("GET", "/api/user/summary/" + UNKNOWN),
                Arguments.of("GET", "/api/user/catapult/" + UNKNOWN),
                Arguments.of("GET", "/api/twitchat/widget/" + UNKNOWN),
                Arguments.of("GET", "/api/twitchat/widget/" + UNKNOWN + "/access"),
                Arguments.of("POST", "/api/twitchat/widget/" + UNKNOWN + "/notifications/flush"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("emptyNotFound")
    void unknownWidgetTokenIsAnEmpty404(String method, String path) throws Exception {
        assertEmpty404(send(method, path, Map.of()));
    }

    @Test
    void tokenWithoutAGameIsAnEmpty404() throws Exception {
        assertEmpty404(send("GET", "/api/game/" + token, Map.of()));
    }

    static Stream<Arguments> typeMismatch() {
        return Stream.of(
                Arguments.of("GET", "/api/game/not-a-uuid", Map.of()),
                Arguments.of("GET", "/api/user/dtdd/abc", Map.of()),
                Arguments.of("GET", "/api/user/catapult/not-a-uuid", Map.of()),
                Arguments.of("GET", "/api/twitchat/widget/not-a-uuid/access", Map.of()),
                Arguments.of("POST", "/api/twitchat/actions/not-a-uuid", Map.of()),
                Arguments.of("GET", "/api/user/game/" + UNKNOWN, Map.of("X-API-Version", "99")));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("typeMismatch")
    void typeMismatchIsBootsJson(String method, String path, Map<String, String> headers) throws Exception {
        HttpResponse<String> response = send(method, path, headers);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.body()).matches(Pattern.compile(BOOT_ERROR.formatted(400, "Bad Request", path)));
    }

    @Test
    void wrongMethodIsBootsJsonWithAllow() throws Exception {
        String path = "/api/game/" + UNKNOWN;
        HttpResponse<String> response = send("POST", path, Map.of());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.headers().firstValue("Allow")).hasValue("GET");
        assertThat(response.body()).matches(Pattern.compile(BOOT_ERROR.formatted(405, "Method Not Allowed", path)));
    }

    @Test
    void badBearerIsAnEmpty401() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/user/game/" + UNKNOWN, Map.of("Authorization", "Bearer not-a-jwt"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
        // resource_metadata names the server's own origin, so this run's random port
        assertThat(response.headers().allValues("WWW-Authenticate"))
                .containsExactly("Bearer error=\"invalid_token\", "
                        + "error_description=\"An error occurred while attempting to decode the Jwt: Malformed token\", "
                        + "error_uri=\"https://tools.ietf.org/html/rfc6750#section-3.1\", "
                        + "resource_metadata=\"http://localhost:" + port + "/.well-known/oauth-protected-resource\"");
    }

    @Test
    void unknownActionTokenAnswersItsResult() throws Exception {
        HttpResponse<String> response = send("POST", "/api/twitchat/actions/" + UNKNOWN, Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.body()).isEqualTo("{\"result\":\"ALREADY_USED_OR_EXPIRED\"}");
    }

    @Test
    void steamCallbackWithoutNonceRedirects() throws Exception {
        HttpResponse<String> response = send("GET", "/api/connect/steam/callback", Map.of());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
        assertThat(response.headers().firstValue("Location"))
                .hasValue("http://localhost/connect/steam/close?error=nonce");
    }

    /** A {@code ResponseStatusException}'s reason ("Unknown cache …") is dropped by Boot's error page. */
    @Test
    void internalReasonNeverReachesTheBodyToday() throws Exception {
        String path = "/api/admin/caches/unknown";
        HttpResponse<String> response = send("GET", path, Map.of());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).matches(Pattern.compile(BOOT_ERROR.formatted(404, "Not Found", path)));
    }

    private static void assertEmpty404(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
    }

    HttpResponse<String> send(String method, String path, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
