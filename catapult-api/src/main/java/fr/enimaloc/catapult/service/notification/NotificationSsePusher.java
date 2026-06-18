package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.event.NotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class NotificationSsePusher {

    private final SseEmitterRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommitted(NotificationCreatedEvent event) {
        for (var userId : event.getRecipientUserIds()) {
            registry.pushToUser(userId, "notification", event.getDto());
        }
    }
}
