package fr.enimaloc.catapult.web.ws;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.auth.WsTicketStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WsHubIntegrationTest {

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

    private BlockingQueue<String> received;

    private WebSocketSession connect() throws Exception {
        received = new LinkedBlockingQueue<>();
        var client = new StandardWebSocketClient();
        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(message.getPayload());
            }
        };
        return client.execute(handler, "ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);
    }

    @Test
    void subscribe_events_global_returns_sub_ok() throws Exception {
        WebSocketSession ws = connect();
        ws.sendMessage(new TextMessage("{\"type\":\"subscribe\",\"channel\":\"events.global\"}"));
        String resp = received.poll(3, TimeUnit.SECONDS);
        assertThat(resp).contains("\"type\":\"sub.ok\"").contains("\"channel\":\"events.global\"");
        ws.close();
    }

    @Test
    void subscribe_notifications_user_unauthenticated_returns_sub_denied() throws Exception {
        WebSocketSession ws = connect();
        ws.sendMessage(new TextMessage("{\"type\":\"subscribe\",\"channel\":\"notifications.user\"}"));
        String resp = received.poll(3, TimeUnit.SECONDS);
        assertThat(resp).contains("\"type\":\"sub.denied\"").contains("\"channel\":\"notifications.user\"");
        ws.close();
    }

    @Test
    void malformed_frame_returns_invalid_frame_error() throws Exception {
        WebSocketSession ws = connect();
        ws.sendMessage(new TextMessage("not json"));
        String resp = received.poll(3, TimeUnit.SECONDS);
        assertThat(resp).contains("\"type\":\"error\"").contains("\"code\":\"INVALID_FRAME\"");
        ws.close();
    }

    @Test
    void receives_ping_within_20_seconds() throws Exception {
        WebSocketSession ws = connect();
        String resp = received.poll(20, TimeUnit.SECONDS);
        assertThat(resp).contains("\"type\":\"ping\"");
        ws.close();
    }

    @Test
    void auth_flow_with_valid_ticket_grants_notifications_user_subscription() throws Exception {
        WebSocketSession ws = connect();
        UUID userId = UUID.randomUUID();
        String ticket = ticketStore.issue(userId, Set.of("ROLE_USER"));

        ws.sendMessage(new TextMessage("{\"type\":\"auth\",\"token\":\"" + ticket + "\"}"));
        String authResp = received.poll(3, TimeUnit.SECONDS);
        assertThat(authResp).contains("\"type\":\"auth.ok\"").contains(userId.toString());

        ws.sendMessage(new TextMessage("{\"type\":\"subscribe\",\"channel\":\"notifications.user\"}"));
        String subResp = received.poll(3, TimeUnit.SECONDS);
        assertThat(subResp).contains("\"type\":\"sub.ok\"").contains("notifications.user");
        ws.close();
    }

    @Test
    void auth_flow_with_invalid_ticket_disconnects() throws Exception {
        WebSocketSession ws = connect();

        ws.sendMessage(new TextMessage("{\"type\":\"auth\",\"token\":\"bogus-not-issued\"}"));
        String err = received.poll(3, TimeUnit.SECONDS);
        assertThat(err).contains("\"type\":\"error\"").contains("INVALID_TICKET");

        // Server should close the socket; give it a moment then verify the session is no longer open.
        Thread.sleep(200);
        assertThat(ws.isOpen()).isFalse();
    }

    @Test
    void search_twitch_categories_returns_results() throws Exception {
        when(apiClient.get(
                ArgumentMatchers.eq("/api/channels/{channelId}/games/search?q={q}"),
                ArgumentMatchers.<ParameterizedTypeReference<List<Map<String, Object>>>>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(
                        Map.of("id", "12345", "name", "Skyrim", "boxArtUrl", "url-1"),
                        Map.of("id", "67890", "name", "Skyrim SE", "boxArtUrl", "url-2")));

        WebSocketSession ws = connect();
        ws.sendMessage(new TextMessage(
                "{\"type\":\"request\",\"id\":\"r-1\",\"action\":\"search.twitch.categories\","
                        + "\"params\":{\"channelId\":\"abc\",\"q\":\"skyr\"}}"));
        String resp = received.poll(3, TimeUnit.SECONDS);
        assertThat(resp)
                .contains("\"type\":\"response\"")
                .contains("\"id\":\"r-1\"")
                .contains("\"ok\":true")
                .contains("Skyrim");
        ws.close();
    }

    @Test
    void rate_limit_kicks_in_after_burst() throws Exception {
        when(apiClient.get(
                ArgumentMatchers.<String>any(),
                ArgumentMatchers.<ParameterizedTypeReference<List<Map<String, Object>>>>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any()))
                .thenReturn(List.of());

        WebSocketSession ws = connect();
        // Fire 12 search calls — limit is 10/s/session.
        int rateLimited = 0;
        for (int i = 0; i < 12; i++) {
            ws.sendMessage(new TextMessage(
                    "{\"type\":\"request\",\"id\":\"r-" + i + "\",\"action\":\"search.twitch.categories\","
                            + "\"params\":{\"channelId\":\"abc\",\"q\":\"x\"}}"));
        }
        // Drain responses; tally RATE_LIMITED.
        long deadline = System.currentTimeMillis() + 5000;
        int collected = 0;
        while (collected < 12 && System.currentTimeMillis() < deadline) {
            String resp = received.poll(500, TimeUnit.MILLISECONDS);
            if (resp == null) break;
            if (resp.contains("RATE_LIMITED")) rateLimited++;
            if (resp.contains("\"type\":\"response\"")) collected++;
        }
        assertThat(rateLimited).as("at least one of 12 search bursts must be rate-limited").isGreaterThanOrEqualTo(1);
        ws.close();
    }

    @Test
    void origin_check_accepts_handshake_with_origin_when_wildcard_allowed() throws Exception {
        // Default profile allows-origin-patterns=*; verify a request with an Origin still completes
        // (regression guard for the interceptor mistakenly blocking everything).
        var headers = new WebSocketHttpHeaders();
        headers.set("Origin", "https://example.com");

        var client = new StandardWebSocketClient();
        WebSocketHandler handler = new TextWebSocketHandler() {};

        var session = client.execute(handler, headers,
                URI.create("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS);
        assertThat(session.isOpen()).isTrue();
        session.close();
    }
}
