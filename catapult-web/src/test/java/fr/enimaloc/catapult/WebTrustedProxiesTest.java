package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * On a real server with the default configuration, only the loopback may relay {@code X-Forwarded-*}. A client on
 * another private address ({@code 127.0.0.2} here, which Tomcat's old default trusted) is not believed, and
 * what catapult-web relays to catapult-api as the client is what it believes. A stub upstream records it.
 */
class WebTrustedProxiesTest extends WebFullContextTestBase {

    private static final String PROPERTY = "server.tomcat.remoteip.internal-proxies";
    private static final AtomicReference<com.sun.net.httpserver.Headers> LAST = new AtomicReference<>();
    private static final HttpServer UPSTREAM;

    static {
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            UPSTREAM.createContext("/", exchange -> {
                LAST.set(exchange.getRequestHeaders());
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            UPSTREAM.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("catapult.backend-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
    }

    @Test
    void privateClientForgingItsAddressIsNotBelieved() throws Exception {
        assertThat(relayed("127.0.0.2", Map.of("X-Forwarded-For", "127.0.0.1")).getFirst("X-Forwarded-For"))
                .isEqualTo("127.0.0.2");
    }

    @Test
    void privateClientForgingTheSchemeIsNotBelieved() throws Exception {
        var seen = relayed("127.0.0.2", Map.of("X-Forwarded-Proto", "https", "X-Forwarded-Host", "evil.example"));
        assertThat(seen.getFirst("X-Forwarded-Proto")).isEqualTo("http");
        assertThat(seen.getFirst("X-Forwarded-Host")).isNotEqualTo("evil.example");
    }

    @Test
    void trustedProxyIsBelieved() throws Exception {
        var seen = relayed("127.0.0.1", Map.of(
                "X-Forwarded-For", "203.0.113.7", "X-Forwarded-Proto", "https", "X-Forwarded-Host", "catapult.example"));
        assertThat(seen.getFirst("X-Forwarded-For")).isEqualTo("203.0.113.7");
        assertThat(seen.getFirst("X-Forwarded-Proto")).isEqualTo("https");
        assertThat(seen.getFirst("X-Forwarded-Host")).isEqualTo("catapult.example");
    }

    @Test
    void defaultTrustsLoopbackOnly() {
        String regex = environment.getProperty(PROPERTY);
        assertThat(regex).isNotNull();
        Pattern pattern = Pattern.compile(regex);
        assertThat(List.of("127.0.0.1", "0:0:0:0:0:0:0:1")).allMatch(a -> pattern.matcher(a).matches());
        assertThat(List.of("127.0.0.2", "10.0.0.1", "172.18.0.1", "192.168.1.5"))
                .noneMatch(a -> pattern.matcher(a).matches());
    }

    @Test
    void composeTrustsTheDockerGatewaysOnly() throws Exception {
        Pattern pattern = Pattern.compile(composeDefault("WEB_TRUSTED_PROXIES"));
        assertThat(List.of("172.17.0.1", "172.31.0.1", "192.168.0.1", "192.168.240.1"))
                .allMatch(a -> pattern.matcher(a).matches());
        assertThat(List.of(
                        "172.18.0.2", "172.18.1.1", "172.32.0.1", "192.168.17.1", "192.168.1.1", "10.250.250.1",
                        "127.0.0.1"))
                .noneMatch(a -> pattern.matcher(a).matches());
    }

    /** The default of {@code WEB_TRUSTED_PROXIES=${WEB_TRUSTED_PROXIES:-default}} in the repository's docker-compose.yml (what a deployment really gets). */
    private static String composeDefault(String variable) throws java.io.IOException {
        java.nio.file.Path compose = java.nio.file.Path.of("docker-compose.yml");
        if (!java.nio.file.Files.exists(compose)) {
            compose = java.nio.file.Path.of("..", "docker-compose.yml");
        }
        String prefix = variable + "=${" + variable + ":-";
        for (String line : java.nio.file.Files.readAllLines(compose)) {
            String trimmed = line.trim();
            int at = trimmed.indexOf(prefix);
            if (at >= 0 && trimmed.endsWith("}")) {
                return trimmed.substring(at + prefix.length(), trimmed.length() - 1);
            }
        }
        throw new IllegalStateException(variable + " default not found in " + compose);
    }

    private com.sun.net.httpserver.Headers relayed(String fromAddress, Map<String, String> headers) throws Exception {
        LAST.set(null);
        int status = rawGet(fromAddress, "/api/game/" + UUID.randomUUID(), headers);
        assertThat(status).isEqualTo(200);
        assertThat(LAST.get()).as("the stub upstream got the relayed request").isNotNull();
        return LAST.get();
    }

    /** Sends the request from a socket bound to {@code fromAddress}; returns the status. */
    private int rawGet(String fromAddress, String path, Map<String, String> headers) throws Exception {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName(fromAddress), 0));
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5000);
            socket.setSoTimeout(15000);
            StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n");
            headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
            request.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(raw);
            String text = raw.toString(StandardCharsets.UTF_8);
            return Integer.parseInt(text.split("\r\n", 2)[0].split(" ")[1]);
        }
    }
}
