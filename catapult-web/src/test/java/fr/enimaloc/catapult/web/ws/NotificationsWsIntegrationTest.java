package fr.enimaloc.catapult.web.ws;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.client.NotificationDto;
import fr.enimaloc.catapult.client.NotificationSnapshotDto;
import fr.enimaloc.catapult.web.ws.auth.WsTicketStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class NotificationsWsIntegrationTest {

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

    @Autowired
    StringRedisTemplate redisTemplate;

    @MockitoBean
    ApiClient apiClient;

    // -----------------------------------------------------------------------
    // Scenario 1: subscribe → snapshot pushed immediately
    // -----------------------------------------------------------------------

    @Test
    void subscribeToNotificationsUser_receivesSnapshotImmediately() throws Exception {
        UUID userId = UUID.randomUUID();

        NotificationDto item1 = new NotificationDto(UUID.randomUUID(), "Title 1", "<p>body</p>",
                "INFO", null, null, null, Instant.now(), false);
        NotificationDto item2 = new NotificationDto(UUID.randomUUID(), "Title 2", "<p>body</p>",
                "WARN", null, null, null, Instant.now(), false);
        NotificationDto item3 = new NotificationDto(UUID.randomUUID(), "Title 3", "<p>body</p>",
                "INFO", null, null, null, Instant.now(), true);
        NotificationSnapshotDto snapshot = new NotificationSnapshotDto(List.of(item1, item2, item3), 2L);
        when(apiClient.notificationSnapshot(userId)).thenReturn(snapshot);

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession ws = connect(received);

        String ticket = ticketStore.issue(userId, Set.of("ROLE_USER"));
        ws.sendMessage(new TextMessage("{\"type\":\"auth\",\"token\":\"" + ticket + "\"}"));
        pollForType(received, "auth.ok", 3);

        ws.sendMessage(new TextMessage("{\"type\":\"subscribe\",\"channel\":\"notifications.user\"}"));
        pollForType(received, "sub.ok", 3);

        String snapshotFrame = pollForType(received, "notification.snapshot", 3);
        assertThat(snapshotFrame)
                .contains("\"name\":\"notification.snapshot\"")
                .contains("\"channel\":\"notifications.user\"")
                .contains("\"unreadCount\":2")
                .contains("Title 1")
                .contains("Title 2")
                .contains("Title 3");

        ws.close();
    }

    // -----------------------------------------------------------------------
    // Scenario 2: markAll command fans out to both sessions of the same user
    // -----------------------------------------------------------------------

    @Test
    void commandMarkAll_emitsAllReadEventOnBothTabs() throws Exception {
        UUID userId = UUID.randomUUID();

        NotificationSnapshotDto emptySnapshot = new NotificationSnapshotDto(List.of(), 0L);
        when(apiClient.notificationSnapshot(userId)).thenReturn(emptySnapshot);

        // When markAll is called, simulate the event that catapult-api would publish
        doAnswer(inv -> {
            Thread.sleep(50); // small delay to ensure subscriptions are registered
            redisTemplate.convertAndSend(
                    "catapult:events:user:" + userId,
                    "{\"name\":\"notification.all.read\",\"data\":{\"unreadCount\":0},\"ts\":"
                            + System.currentTimeMillis() + "}"
            );
            return null;
        }).when(apiClient).notificationMarkAll(userId);

        BlockingQueue<String> receivedA = new LinkedBlockingQueue<>();
        BlockingQueue<String> receivedB = new LinkedBlockingQueue<>();
        WebSocketSession wsA = connect(receivedA);
        WebSocketSession wsB = connect(receivedB);

        String ticketA = ticketStore.issue(userId, Set.of("ROLE_USER"));
        wsA.sendMessage(new TextMessage("{\"type\":\"auth\",\"token\":\"" + ticketA + "\"}"));
        pollForType(receivedA, "auth.ok", 3);

        String ticketB = ticketStore.issue(userId, Set.of("ROLE_USER"));
        wsB.sendMessage(new TextMessage("{\"type\":\"auth\",\"token\":\"" + ticketB + "\"}"));
        pollForType(receivedB, "auth.ok", 3);

        wsA.sendMessage(new TextMessage("{\"type\":\"subscribe\",\"channel\":\"notifications.user\"}"));
        pollForType(receivedA, "sub.ok", 3);
        // drain snapshot for A
        pollForType(receivedA, "notification.snapshot", 3);

        wsB.sendMessage(new TextMessage("{\"type\":\"subscribe\",\"channel\":\"notifications.user\"}"));
        pollForType(receivedB, "sub.ok", 3);
        // drain snapshot for B
        pollForType(receivedB, "notification.snapshot", 3);

        // Send markAll command from session A
        wsA.sendMessage(new TextMessage(
                "{\"type\":\"command\",\"action\":\"notification.markAll\",\"params\":{}}"));

        String eventA = pollForType(receivedA, "notification.all.read", 5);
        String eventB = pollForType(receivedB, "notification.all.read", 5);

        assertThat(eventA)
                .contains("\"name\":\"notification.all.read\"")
                .contains("\"channel\":\"notifications.user\"");
        assertThat(eventB)
                .contains("\"name\":\"notification.all.read\"")
                .contains("\"channel\":\"notifications.user\"");

        wsA.close();
        wsB.close();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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

    private String pollForType(BlockingQueue<String> q, String type, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + (long) timeoutSeconds * 1_000_000_000L;
        String marker = "\"" + type + "\"";
        while (System.nanoTime() < deadline) {
            String frame = q.poll(500, TimeUnit.MILLISECONDS);
            if (frame == null) continue;
            if (frame.contains(marker)) return frame;
        }
        throw new AssertionError("Did not receive a frame containing " + marker
                + " within " + timeoutSeconds + "s");
    }
}
