package fr.enimaloc.catapult.web.ws;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the web app with a strict allowed-origin-patterns config so we can
 * verify the handshake interceptor refuses a request whose {@code Origin} is
 * not on the list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "catapult.ws.allowed-origin-patterns=https://catapult.example.com")
class WsOriginCheckIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @LocalServerPort
    int port;

    @Test
    void handshake_with_unlisted_origin_is_rejected() {
        var headers = new WebSocketHttpHeaders();
        headers.set("Origin", "https://evil.example.com");

        var client = new StandardWebSocketClient();
        WebSocketHandler handler = new TextWebSocketHandler() {};

        // Spring's built-in allowed-origin-patterns kicks in first and produces an HTTP 403 on the
        // handshake; the client surfaces that as an ExecutionException with cause UpgradeException.
        assertThatThrownBy(() -> client.execute(handler, headers,
                URI.create("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void handshake_with_listed_origin_is_accepted() throws Exception {
        var headers = new WebSocketHttpHeaders();
        headers.set("Origin", "https://catapult.example.com");

        var client = new StandardWebSocketClient();
        WebSocketHandler handler = new TextWebSocketHandler() {};

        var session = client.execute(handler, headers,
                URI.create("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS);
        assertThat(session.isOpen()).isTrue();
        session.close();
    }
}
