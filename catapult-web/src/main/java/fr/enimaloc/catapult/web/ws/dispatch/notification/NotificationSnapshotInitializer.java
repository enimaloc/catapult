package fr.enimaloc.catapult.web.ws.dispatch.notification;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.client.NotificationSnapshotDto;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.codec.JsonMessageCodec;
import fr.enimaloc.catapult.web.ws.codec.msg.EventMessage;
import fr.enimaloc.catapult.web.ws.dispatch.SubscriptionInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Pushes the current notification snapshot to a session immediately after it
 * subscribes to {@code notifications.user}, so the client never needs to issue
 * a separate request to hydrate its notification list.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSnapshotInitializer implements SubscriptionInitializer {

    private final ApiClient apiClient;
    private final JsonMessageCodec codec;

    @Override
    public String publicChannel() {
        return "notifications.user";
    }

    @Override
    public void onSubscribe(WsSession session) {
        UUID userId = session.userId().orElse(null);
        if (userId == null) {
            return;
        }
        NotificationSnapshotDto snap = apiClient.notificationSnapshot(userId);
        if (snap == null) {
            log.warn("notification snapshot returned null for user {}, skipping push", userId);
            return;
        }
        EventMessage event = new EventMessage("notifications.user", "notification.snapshot", snap);
        session.send(codec.encode(event));
    }
}
