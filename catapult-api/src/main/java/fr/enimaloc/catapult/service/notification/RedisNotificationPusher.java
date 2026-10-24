package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.event.NotificationAllReadEvent;
import fr.enimaloc.catapult.event.NotificationCreatedEvent;
import fr.enimaloc.catapult.common.event.ChannelEvents;
import fr.enimaloc.catapult.event.NotificationDeletedEvent;
import fr.enimaloc.catapult.event.NotificationDeliveryPendingEvent;
import fr.enimaloc.catapult.event.NotificationReadEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bridges in-process notification events (fired after commit by
 * {@code NotificationService}) onto the Redis pub/sub bus consumed by catapult-web's
 * WebSocket hub.
 */
@Component
@RequiredArgsConstructor
public class RedisNotificationPusher {

    /** Ids per message: a broadcast can name thousands of members, one message must stay small. */
    static final int DELIVERY_PENDING_CHUNK = 500;

    private final RedisEventPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommitted(NotificationCreatedEvent event) {
        for (var userId : event.getRecipientUserIds()) {
            publisher.publishUser(userId, "notification.created", event.getDto());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRead(NotificationReadEvent ev) {
        publisher.publishUser(ev.userId(), "notification.read.changed",
                Map.of("notificationId", ev.notificationId().toString(),
                        "unreadCount", ev.newUnreadCount()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAllRead(NotificationAllReadEvent ev) {
        publisher.publishUser(ev.userId(), "notification.all.read",
                Map.of("unreadCount", 0L));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeleted(NotificationDeletedEvent ev) {
        publisher.publishUser(ev.userId(), "notification.deleted",
                Map.of("notificationId", ev.notificationId().toString(),
                       "unreadCount", ev.newUnreadCount()));
    }

    /** After commit, so that the flush catapult-web asks for in return finds the deliveries. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeliveryPending(NotificationDeliveryPendingEvent ev) {
        List<UUID> ids = ev.userIds();
        for (int from = 0; from < ids.size(); from += DELIVERY_PENDING_CHUNK) {
            List<UUID> chunk = List.copyOf(ids.subList(from, Math.min(ids.size(), from + DELIVERY_PENDING_CHUNK)));
            publisher.publishNotificationDelivery(ChannelEvents.NOTIFICATION_DELIVERY_PENDING,
                    new ChannelEvents.NotificationDeliveryPending(chunk));
        }
    }
}
