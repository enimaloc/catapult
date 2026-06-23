package fr.enimaloc.catapult.web.ws;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.auth.WsAuthContext;
import fr.enimaloc.catapult.web.ws.auth.WsTicketStore;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End-to-end verification that an authenticated WebSocket session propagates
 * its JWT into {@code ApiClient}-based upstream calls invoked by request
 * handlers, even though there is no enclosing HTTP request on the dispatch
 * thread.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WsJwtPropagationIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    WsTicketStore ticketStore;

    @MockitoBean
    ApiClient apiClient;

    @Test
    void search_handler_sees_session_jwt_via_ws_auth_context() throws Exception {
        AtomicReference<String> seenJwt = new AtomicReference<>();
        when(apiClient.get(eq("/api/channels/{channelId}/games/search?q={q}"),
                any(org.springframework.core.ParameterizedTypeReference.class),
                any(), any()))
                .thenAnswer((InvocationOnMock inv) -> {
                    seenJwt.set(WsAuthContext.get());
                    return List.of();
                });

        var received = new LinkedBlockingQueue<String>();
        WebSocketSession ws = connect(received);

        UUID userId = UUID.randomUUID();
        String issuedJwt = "issued-jwt-xyz";
        String ticket = ticketStore.issue(userId, Set.of("ROLE_USER"), issuedJwt);

        ws.sendMessage(new TextMessage("{\"type\":\"auth\",\"token\":\"" + ticket + "\"}"));
        // Drain auth.ok
        String authOk = received.poll(3, TimeUnit.SECONDS);
        assertThat(authOk).contains("auth.ok");

        ws.sendMessage(new TextMessage(
                "{\"type\":\"request\",\"id\":\"r-1\",\"action\":\"search.twitch.categories\","
                        + "\"params\":{\"channelId\":\"abc\",\"q\":\"sky\"}}"));

        String resp = pollFor(received, "\"id\":\"r-1\"");
        assertThat(resp).contains("\"ok\":true");
        assertThat(seenJwt.get()).isEqualTo(issuedJwt);

        ws.close();
    }

    @Test
    void unauthenticated_session_propagates_no_jwt() throws Exception {
        AtomicReference<String> seenJwt = new AtomicReference<>("UNSET");
        when(apiClient.get(eq("/api/channels/{channelId}/games/search?q={q}"),
                any(org.springframework.core.ParameterizedTypeReference.class),
                any(), any()))
                .thenAnswer((InvocationOnMock inv) -> {
                    seenJwt.set(WsAuthContext.get());
                    return List.of();
                });

        var received = new LinkedBlockingQueue<String>();
        WebSocketSession ws = connect(received);

        ws.sendMessage(new TextMessage(
                "{\"type\":\"request\",\"id\":\"r-2\",\"action\":\"search.twitch.categories\","
                        + "\"params\":{\"channelId\":\"abc\",\"q\":\"sky\"}}"));

        String resp = pollFor(received, "\"id\":\"r-2\"");
        assertThat(resp).contains("\"ok\":true");
        assertThat(seenJwt.get()).isNull();

        ws.close();
    }

    private WebSocketSession connect(BlockingQueue<String> received) throws Exception {
        var client = new StandardWebSocketClient();
        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(message.getPayload());
            }
        };
        return client.execute(handler, "ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);
    }

    private static String pollFor(BlockingQueue<String> q, String needle) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String msg = q.poll(500, TimeUnit.MILLISECONDS);
            if (msg != null && msg.contains(needle)) return msg;
        }
        return null;
    }
}
