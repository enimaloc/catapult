package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * On a real server, a request relayed for a remote client ({@code X-Forwarded-For}) is that client, not the
 * loopback address it arrives from: it does not get the loopback's admin access.
 */
@Import(FullContextContainers.class)
class ForwardedClientAccessTest extends FullContextTestBase {

    @LocalServerPort
    int port;

    @Test
    void relayedClientIsNotLoopback() throws Exception {
        assertThat(adminFlags("203.0.113.7").statusCode()).isEqualTo(401);
        assertThat(adminFlags(null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> adminFlags(String forwardedFor) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/internal/admin/flags")).GET();
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
