package fr.enimaloc.catapult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Loads the whole application with the production bean set, so a dependency cycle or a missing bean fails the build.
 * The main resources are added as a config location (no {@code optional:}), so a wrong path fails loudly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.additional-location=file:./src/main/resources/")
@TestPropertySource(locations = "classpath:full-context.properties")
@DirtiesContext
public abstract class FullContextTestBase {

    @Autowired
    protected ApplicationContext context;

    @Autowired
    protected Environment environment;

    @LocalManagementPort
    protected int managementPort;

    protected HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
