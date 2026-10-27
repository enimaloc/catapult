package fr.enimaloc.catapult.twitchat;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Mock mode has no catapult-api: nothing is ever pending. */
@Component
@Profile("mock")
public class MockNotificationFlusher implements NotificationFlusher {

    @Override
    public int flushPage(String jwt) {
        return 200;
    }

    @Override
    public int flushWidget(UUID token) {
        return 200;
    }
}
