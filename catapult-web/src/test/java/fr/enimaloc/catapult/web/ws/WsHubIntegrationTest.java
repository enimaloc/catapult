package fr.enimaloc.catapult.web.ws;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WsHubIntegrationTest {

    @LocalServerPort
    int port;

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
}
