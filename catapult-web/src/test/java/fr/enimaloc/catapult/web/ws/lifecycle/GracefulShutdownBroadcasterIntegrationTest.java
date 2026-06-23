package fr.enimaloc.catapult.web.ws.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
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

/**
 * Verifies that firing a {@link ContextClosedEvent} programmatically — the
 * same event Spring publishes during a graceful shutdown — causes connected
 * WebSocket clients to receive a {@code maintenance.imminent} broadcast.
 *
 * <p>We fire the event manually rather than calling {@code context.close()}
 * because the latter would tear down the embedded Tomcat and break the
 * remaining lifecycle of the test framework. The broadcaster reacts to the
 * event itself, not to the actual shutdown — so the publication is observable
 * with the server still running.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GracefulShutdownBroadcasterIntegrationTest {

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
    ApplicationContext context;

    @Test
    void context_closed_event_triggers_maintenance_imminent_to_subscribed_clients() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        var client = new StandardWebSocketClient();
        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(message.getPayload());
            }
        };
        WebSocketSession ws = client.execute(handler, "ws://localhost:" + port + "/ws")
                .get(5, TimeUnit.SECONDS);

        ws.sendMessage(new TextMessage("{\"type\":\"subscribe\",\"channel\":\"events.global\"}"));
        String subOk = pollForType(received, "sub.ok", 3);
        assertThat(subOk).contains("\"channel\":\"events.global\"");

        // Fire the same event Spring would publish on real shutdown.
        context.publishEvent(new ContextClosedEvent(context));

        String event = pollForType(received, "event", 5);
        assertThat(event)
                .contains("\"type\":\"event\"")
                .contains("\"channel\":\"events.global\"")
                .contains("\"name\":\"maintenance.imminent\"")
                .contains("\"reason\":\"shutdown\"")
                .contains("\"etaSeconds\":10");

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
