package fr.enimaloc.catapult.web.ws;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RedisEventSubscriberIntegrationTest {

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
    StringRedisTemplate redisTemplate;

    @Test
    void publish_on_events_global_is_delivered_to_subscribed_ws_client() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        var client = new StandardWebSocketClient();
        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(message.getPayload());
            }
        };
        WebSocketSession ws = client.execute(handler, "ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);

        // Subscribe to events.global
        ws.sendMessage(new TextMessage("{\"type\":\"subscribe\",\"channel\":\"events.global\"}"));
        String subOk = pollForType(received, "sub.ok", 3);
        assertThat(subOk).contains("\"channel\":\"events.global\"");

        // Publish on the Redis channel — give the listener a moment to register first
        Thread.sleep(300);
        redisTemplate.convertAndSend(
                "catapult:events:global",
                "{\"name\":\"maintenance.scheduled\",\"data\":{\"message\":\"hello\"},\"ts\":1750608000000}"
        );

        String event = pollForType(received, "event", 5);
        assertThat(event)
                .contains("\"type\":\"event\"")
                .contains("\"channel\":\"events.global\"")
                .contains("\"name\":\"maintenance.scheduled\"")
                .contains("\"message\":\"hello\"");
        ws.close();
    }

    /** Reads until a frame of the requested {@code type} appears (skips pings, sub.ok, etc.). */
    private String pollForType(BlockingQueue<String> q, String type, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + (long) timeoutSeconds * 1_000_000_000L;
        String marker = "\"type\":\"" + type + "\"";
        while (System.nanoTime() < deadline) {
            String frame = q.poll(500, TimeUnit.MILLISECONDS);
            if (frame == null) continue;
            if (frame.contains(marker)) return frame;
        }
        throw new AssertionError("Did not receive a frame of type=" + type + " within " + timeoutSeconds + "s");
    }
}
