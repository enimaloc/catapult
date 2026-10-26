package fr.enimaloc.catapult.api.userapi;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.FullContextTestBase;
import fr.enimaloc.catapult.RawHttp;
import fr.enimaloc.catapult.RawHttp.RawResponse;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.repository.igdb.IgdbGameDetailsRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * On a real server, the "more" links follow the origin Tomcat believes: the relay's {@code X-Forwarded-*} from a
 * trusted proxy (loopback), the plain {@code Host} for anyone else.
 */
@Import(FullContextContainers.class)
class PublicLinksForwardingTest extends FullContextTestBase {

    private static final String PATH = "/api/user/igdb/9001";

    @LocalServerPort
    int port;

    @Autowired
    IgdbGameDetailsRepository repository;

    @BeforeEach
    void saveGame() {
        IgdbGameDetails d = new IgdbGameDetails();
        d.setIgdbId("9001");
        d.setSlug("forwarded-game");
        d.setDlcIds(List.of("9002"));
        d.setDlcNames(List.of("Forwarded DLC"));
        d.setFetchedAt(Instant.now());
        repository.save(d);
    }

    private String viaLoopback(Map<String, String> forwarded) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH)).GET();
        forwarded.forEach(request::header);
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @Test
    void trustedProxyBuildsTheLinksFromItsForwardedHeaders() throws Exception {
        String body = viaLoopback(Map.of(
                "X-Forwarded-Proto", "https", "X-Forwarded-Host", "catapult.example", "X-Forwarded-Port", "443"));
        assertThat(body).contains("\"more\":\"https://catapult.example/api/user/igdb/9002\"");
    }

    @Test
    void trustedProxyKeepsANonDefaultPort() throws Exception {
        String body = viaLoopback(Map.of(
                "X-Forwarded-Proto", "https", "X-Forwarded-Host", "catapult.example", "X-Forwarded-Port", "8443"));
        assertThat(body).contains("\"more\":\"https://catapult.example:8443/api/user/igdb/9002\"");
    }

    @Test
    void untrustedForwardedHeadersAreIgnored() throws Exception {
        RawResponse response = RawHttp.get(
                port, "127.0.0.2", PATH, Map.of("X-Forwarded-Proto", "https", "X-Forwarded-Host", "evil.example"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"more\":\"http://127.0.0.1:" + port + "/api/user/igdb/9002\"");
        assertThat(response.body()).doesNotContain("evil.example");
    }

    @Test
    void directRequestUsesItsOwnOrigin() throws Exception {
        String body = viaLoopback(Map.of());
        assertThat(body).contains("\"more\":\"http://127.0.0.1:" + port + "/api/user/igdb/9002\"");
        assertThat(body).doesNotContain("http://localhost/");
    }
}
