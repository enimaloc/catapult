package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.event.NotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges the in-process {@link NotificationCreatedEvent} (fired after commit by
 * {@code NotificationService}) onto the Redis pub/sub bus consumed by catapult-web's
 * WebSocket hub.
 */
@Component
@RequiredArgsConstructor
public class RedisNotificationPusher {

    private final RedisEventPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommitted(NotificationCreatedEvent event) {
        for (var userId : event.getRecipientUserIds()) {
            publisher.publishUser(userId, "notification.created", event.getDto());
        }
    }
}
