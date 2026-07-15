package fr.enimaloc.catapult.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link ApiClient#get(String, Class, Locale, Object...)} forwards the
 * caller's {@link Locale} as an {@code Accept-Language} header, so catapult-api's
 * {@code AcceptHeaderLocaleResolver} resolves the browser's locale instead of
 * always falling back to the JVM default on this server-to-server call.
 */
class ApiClientLocalePropagationTest {

    private HttpServer server;
    private AtomicReference<String> lastAcceptLanguage;
    private ApiClient apiClient;

    @BeforeEach
    void setUp() throws Exception {
        lastAcceptLanguage = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/echo", exchange -> {
            lastAcceptLanguage.set(exchange.getRequestHeaders().getFirst("Accept-Language"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        apiClient = new ApiClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void forwards_locale_as_accept_language_header() {
        apiClient.get("/api/echo", Map.class, Locale.forLanguageTag("fr-FR"));

        assertThat(lastAcceptLanguage.get()).isEqualTo("fr-FR");
    }
}
