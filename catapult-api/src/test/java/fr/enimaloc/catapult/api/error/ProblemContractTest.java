package fr.enimaloc.catapult.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.FullContextTestBase;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.JwtService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The problem answers of the internal routes, on a real server: the application's own message converters and
 * {@code MessageSource} are in play, which a standalone MockMvc would not show.
 */
@Import(FullContextContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProblemContractTest extends FullContextTestBase {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    JwtService jwtService;

    String memberJwt;

    @BeforeAll
    void fixture() {
        UserAccount account = new UserAccount();
        account.setTwitchUsername("problem-contract");
        account.setTwitchId("problem-contract-twitch");
        account.setStatus(UserAccount.Status.ACTIVE);
        memberJwt = jwtService.generateForUser(accounts.save(account), List.of("ROLE_USER"));
    }

    /** Replaces Task 1's pin of the dropped reason: the reason is now the problem's detail. */
    @Test
    void internalErrorIsAProblem() throws Exception {
        String path = "/api/admin/caches/unknown";
        HttpResponse<String> response = send(path, Map.of());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(PROBLEM_JSON);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo("not_found");
        assertThat(body.path("detail").asString()).isEqualTo("Unknown cache unknown");
        assertThat(body.path("instance").asString()).isEqualTo(path);
    }

    @Test
    void detailIsEnglishWhateverTheLanguage() throws Exception {
        HttpResponse<String> response = send("/api/admin/members/not-a-uuid", Map.of("Accept-Language", "fr"));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo("invalid_request");
        assertThat(body.path("detail").asString()).startsWith("Failed to convert").doesNotContain("problemDetail.");
    }

    @Test
    void problemOnAnEventStreamRouteKeepsItsStatus() throws Exception {
        HttpResponse<String> response = send(
                "/api/channels/nobody-here/connections",
                Map.of("Authorization", "Bearer " + memberJwt, "Accept", "text/event-stream"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(PROBLEM_JSON);
    }

    @Test
    void unmappedInternalPathIsAProblem() throws Exception {
        HttpResponse<String> response = send("/api/nonexistent", Map.of());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("not_found");
    }

    HttpResponse<String> send(String path, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
