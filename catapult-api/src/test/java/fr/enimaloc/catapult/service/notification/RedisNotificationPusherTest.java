package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.Severity;
import fr.enimaloc.catapult.event.NotificationAllReadEvent;
import fr.enimaloc.catapult.event.NotificationCreatedEvent;
import fr.enimaloc.catapult.event.NotificationDeletedEvent;
import fr.enimaloc.catapult.event.NotificationReadEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisNotificationPusherTest {

    @Mock RedisEventPublisher publisher;

    @Test
    void onCommitted_publishes_one_event_per_recipient() {
        RedisNotificationPusher pusher = new RedisNotificationPusher(publisher);
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        NotificationDto dto = new NotificationDto(UUID.randomUUID(), "T", "<p>B</p>",
                Severity.INFO, null, null, null, Instant.now(), false);
        NotificationCreatedEvent ev = new NotificationCreatedEvent(this, List.of(u1, u2), dto);

        pusher.onCommitted(ev);

        verify(publisher).publishUser(u1, "notification.created", dto);
        verify(publisher).publishUser(u2, "notification.created", dto);
    }

    @Test
    void onRead_publishes_read_changed() {
        RedisNotificationPusher pusher = new RedisNotificationPusher(publisher);
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        NotificationReadEvent ev = new NotificationReadEvent(userId, notifId, 5L);

        pusher.onRead(ev);

        verify(publisher).publishUser(userId, "notification.read.changed",
                Map.of("notificationId", notifId.toString(), "unreadCount", 5L));
    }

    @Test
    void onAllRead_publishes_all_read() {
        RedisNotificationPusher pusher = new RedisNotificationPusher(publisher);
        UUID userId = UUID.randomUUID();
        NotificationAllReadEvent ev = new NotificationAllReadEvent(userId);

        pusher.onAllRead(ev);

        verify(publisher).publishUser(userId, "notification.all.read", Map.of("unreadCount", 0L));
    }

    @Test
    void onDeleted_publishes_deleted() {
        RedisNotificationPusher pusher = new RedisNotificationPusher(publisher);
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        NotificationDeletedEvent ev = new NotificationDeletedEvent(userId, notifId, 2L);

        pusher.onDeleted(ev);

        verify(publisher).publishUser(userId, "notification.deleted",
                Map.of("notificationId", notifId.toString(), "unreadCount", 2L));
    }

    @Test
    void onDeliveryPending_publishesTheIds_inChunksOf500() {
        RedisNotificationPusher pusher = new RedisNotificationPusher(publisher);
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(1001).toList();

        pusher.onDeliveryPending(new fr.enimaloc.catapult.event.NotificationDeliveryPendingEvent(ids));

        verify(publisher).publishNotificationDelivery("notification.delivery.pending",
                new fr.enimaloc.catapult.common.event.ChannelEvents.NotificationDeliveryPending(ids.subList(0, 500)));
        verify(publisher).publishNotificationDelivery("notification.delivery.pending",
                new fr.enimaloc.catapult.common.event.ChannelEvents.NotificationDeliveryPending(ids.subList(500, 1000)));
        verify(publisher).publishNotificationDelivery("notification.delivery.pending",
                new fr.enimaloc.catapult.common.event.ChannelEvents.NotificationDeliveryPending(ids.subList(1000, 1001)));
    }
}
