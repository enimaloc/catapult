package fr.enimaloc.catapult.security;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.FullContextTestBase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On a real server with the default configuration, only the loopback may relay {@code X-Forwarded-*}. A client on
 * another private address ({@code 127.0.0.2} here, which Tomcat's old default trusted) is not believed: it cannot
 * pass for the loopback admin, nor choose the scheme and host the server believes in. What the server believes is
 * read from a test-only echo route.
 */
@Import({FullContextContainers.class, TrustedProxiesTest.EchoConfig.class})
class TrustedProxiesTest extends FullContextTestBase {

    private static final String ECHO = "/api/internal/config/test-echo-peer";
    private static final String PROPERTY = "server.tomcat.remoteip.internal-proxies";

    @LocalServerPort
    int port;

    @TestConfiguration(proxyBeanMethods = false)
    static class EchoConfig {

        @RestController
        static class EchoController {

            @GetMapping(ECHO)
            String echo(HttpServletRequest request) {
                return request.getScheme() + "|" + request.getServerName() + "|" + request.getRemoteAddr();
            }
        }
    }

    record RawResponse(int status, Map<String, String> headers, String body) {}

    @Test
    void privateClientForgingLoopbackIsNotAdmin() throws Exception {
        RawResponse response =
                rawGet("127.0.0.2", "/api/internal/admin/flags", Map.of("X-Forwarded-For", "127.0.0.1"));
        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void privateClientIsNotLoopback() throws Exception {
        assertThat(rawGet("127.0.0.2", "/api/internal/admin/flags", Map.of()).status()).isEqualTo(401);
    }

    @Test
    void untrustedForwardedHostIsIgnored() throws Exception {
        RawResponse response = rawGet(
                "127.0.0.2", ECHO, Map.of("X-Forwarded-Host", "evil.example", "X-Forwarded-Proto", "https"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("evil.example").startsWith("http|").endsWith("|127.0.0.2");
    }

    @Test
    void trustedProxyForwardedHostIsHonoured() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ECHO))
                .header("X-Forwarded-Host", "catapult.example")
                .header("X-Forwarded-Proto", "https")
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).startsWith("https|catapult.example|");
    }

    @Test
    void defaultTrustsLoopbackOnly() {
        String regex = environment.getProperty(PROPERTY);
        assertThat(regex).isNotNull();
        Pattern pattern = Pattern.compile(regex);
        assertThat(List.of("127.0.0.1", "0:0:0:0:0:0:0:1")).allMatch(a -> pattern.matcher(a).matches());
        assertThat(List.of(
                        "127.0.0.2", "10.0.0.1", "10.250.250.7", "172.18.0.5", "192.168.1.5", "100.64.0.1",
                        "169.254.1.1"))
                .noneMatch(a -> pattern.matcher(a).matches());
    }

    @Test
    void composeTrustsTheApiNetworkOnly() throws Exception {
        Pattern pattern = Pattern.compile(composeDefault("API_TRUSTED_PROXIES"));
        assertThat(List.of("10.250.250.2", "10.250.250.7", "10.250.250.254"))
                .allMatch(a -> pattern.matcher(a).matches());
        assertThat(List.of("10.250.250.1", "10.250.250.255", "10.250.251.7", "172.18.0.5"))
                .noneMatch(a -> pattern.matcher(a).matches());
    }

    /** The default of {@code API_TRUSTED_PROXIES=${API_TRUSTED_PROXIES:-default}} in the repository's docker-compose.yml (what a deployment really gets). */
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

    private RawResponse rawGet(String fromAddress, String path, Map<String, String> headers) throws Exception {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName(fromAddress), 0));
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5000);
            socket.setSoTimeout(10000);
            StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n");
            headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
            request.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            in.transferTo(raw);
            String text = raw.toString(StandardCharsets.UTF_8);
            int split = text.indexOf("\r\n\r\n");
            String head = split < 0 ? text : text.substring(0, split);
            String body = split < 0 ? "" : text.substring(split + 4);
            String[] lines = head.split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> parsed = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    parsed.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
                }
            }
            return new RawResponse(status, parsed, body.trim());
        }
    }
}
