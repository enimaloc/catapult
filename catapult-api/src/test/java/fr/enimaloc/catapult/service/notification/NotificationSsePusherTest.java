package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.Notification;
import fr.enimaloc.catapult.event.NotificationCreatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationSsePusherTest {

    @Mock SseEmitterRegistry registry;

    @Test
    void onAfterCommit_pushesToEachRecipient() {
        NotificationSsePusher pusher = new NotificationSsePusher(registry);
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        NotificationDto dto = new NotificationDto(UUID.randomUUID(), "T", "<p>B</p>",
                Notification.Severity.INFO, null, null, null, Instant.now(), false);
        NotificationCreatedEvent ev = new NotificationCreatedEvent(this, List.of(u1, u2), dto);

        pusher.onCommitted(ev);

        verify(registry).pushToUser(u1, "notification", dto);
        verify(registry).pushToUser(u2, "notification", dto);
    }
}
