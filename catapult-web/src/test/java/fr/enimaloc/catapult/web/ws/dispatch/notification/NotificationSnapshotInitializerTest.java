package fr.enimaloc.catapult.web.ws.dispatch.notification;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.client.NotificationDto;
import fr.enimaloc.catapult.client.NotificationSnapshotDto;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.codec.JsonMessageCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationSnapshotInitializer}.
 */
class NotificationSnapshotInitializerTest {

    private ApiClient apiClient;
    private NotificationSnapshotInitializer initializer;

    @BeforeEach
    void setUp() {
        apiClient = mock(ApiClient.class);
        initializer = new NotificationSnapshotInitializer(apiClient, new JsonMessageCodec());
    }

    private WsSession authenticatedSession(UUID userId) throws Exception {
        WebSocketSession springSession = mock(WebSocketSession.class);
        when(springSession.getId()).thenReturn("ws-" + userId);
        when(springSession.isOpen()).thenReturn(true);
        WsSession session = new WsSession(springSession);
        session.authenticate(userId, Set.of("ROLE_USER"), "test-jwt");
        return session;
    }

    private WsSession anonymousSession() {
        WebSocketSession springSession = mock(WebSocketSession.class);
        when(springSession.getId()).thenReturn("ws-anon");
        return new WsSession(springSession);
    }

    @Test
    void publicChannel_isNotificationsUser() {
        assertThat(initializer.publicChannel()).isEqualTo("notifications.user");
    }

    @Test
    void onSubscribe_sendsSnapshotEvent() throws Exception {
        UUID userId = UUID.randomUUID();
        WsSession session = authenticatedSession(userId);

        NotificationSnapshotDto snap = new NotificationSnapshotDto(
                List.of(new NotificationDto(
                        UUID.randomUUID(), "Hello", "<p>body</p>", "INFO",
                        null, null, null, Instant.now(), false)),
                3L);
        when(apiClient.notificationSnapshot(userId)).thenReturn(snap);

        List<String> sent = new ArrayList<>();
        doAnswer(inv -> {
            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(session.springSession()).sendMessage(any());

        initializer.onSubscribe(session);

        assertThat(sent).hasSize(1);
        String frame = sent.get(0);
        assertThat(frame).contains("\"type\":\"event\"");
        assertThat(frame).contains("\"channel\":\"notifications.user\"");
        assertThat(frame).contains("\"name\":\"notification.snapshot\"");
        assertThat(frame).contains("\"unreadCount\":3");
    }

    @Test
    void onSubscribe_skipsAnonymousSession() throws Exception {
        WsSession anon = anonymousSession();

        initializer.onSubscribe(anon);

        verifyNoInteractions(apiClient);
        verify(anon.springSession(), never()).sendMessage(any());
    }

    @Test
    void onSubscribe_skipsWhenSnapshotReturnsNull() throws Exception {
        UUID userId = UUID.randomUUID();
        WsSession session = authenticatedSession(userId);
        when(apiClient.notificationSnapshot(userId)).thenReturn(null);

        // Should not throw and should not send anything
        initializer.onSubscribe(session);

        verify(session.springSession(), never()).sendMessage(any());
    }
}
