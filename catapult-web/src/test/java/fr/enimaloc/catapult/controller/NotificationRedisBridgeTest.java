package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.event.notification.BroadcastStreamEvent;
import fr.enimaloc.catapult.event.notification.NotificationStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationRedisBridgeTest {

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final UUID account = UUID.randomUUID();
    private final String user = "catapult:events:user:" + account;

    private void forward(String channel, String body) {
        NotificationRedisBridge.forward(events, channel, body);
    }

    @Test
    void forwardsAKnownBroadcast() {
        forward("catapult:events:global",
                "{\"name\":\"alert.warning\",\"data\":{\"title\":\"t\",\"body\":\"b\",\"ttlSeconds\":5},\"ts\":1}");

        verify(events).publishEvent(new BroadcastStreamEvent("alert.warning",
                Map.of("title", "t", "body", "b", "ttlSeconds", 5)));
    }

    @Test
    void ignoresAnUnknownBroadcast() {
        forward("catapult:events:global", "{\"name\":\"foo.bar\",\"data\":{},\"ts\":1}");
        verifyNoInteractions(events);
    }

    @Test
    void forwardsAnAccountsNotificationEvents_underTheirStreamNames() {
        forward(user, "{\"name\":\"notification.created\",\"data\":{\"id\":\"n1\",\"title\":\"t\"},\"ts\":1}");
        forward(user, "{\"name\":\"notification.read.changed\",\"data\":{\"notificationId\":\"n1\",\"unreadCount\":2},\"ts\":2}");
        forward(user, "{\"name\":\"notification.all.read\",\"data\":{\"unreadCount\":0},\"ts\":3}");
        forward(user, "{\"name\":\"notification.deleted\",\"data\":{\"notificationId\":\"n1\",\"unreadCount\":1},\"ts\":4}");

        verify(events).publishEvent(new NotificationStreamEvent(account, NotificationStreamEvent.CREATED,
                Map.of("id", "n1", "title", "t")));
        verify(events).publishEvent(new NotificationStreamEvent(account, NotificationStreamEvent.READ,
                Map.of("notificationId", "n1", "unreadCount", 2)));
        verify(events).publishEvent(new NotificationStreamEvent(account, NotificationStreamEvent.ALL_READ,
                Map.of("unreadCount", 0)));
        verify(events).publishEvent(new NotificationStreamEvent(account, NotificationStreamEvent.DELETED,
                Map.of("notificationId", "n1", "unreadCount", 1)));
    }

    @Test
    void ignoresOtherEvents_otherChannels_andGarbage() {
        forward(user, "{\"name\":\"bot.toggled\",\"data\":{}}");
        forward(user, "{\"name\":\"alert.info\",\"data\":{}}");
        forward("catapult:events:global", "{\"name\":\"notification.created\",\"data\":{}}");
        forward("catapult:events:channel:" + account, "{\"name\":\"notification.created\",\"data\":{}}");
        forward("catapult:events:user:not-a-uuid", "{\"name\":\"notification.created\",\"data\":{}}");
        forward(user, "{oops");
        forward("catapult:events:global", "{oops");
        forward(user, "{\"data\":{}}");
        forward("catapult:events:global", "{\"data\":{}}");
        forward(user, "[1,2]");
        forward(user, "{\"name\":\"notification.created\",\"data\":\"x\"}");
        forward(user, "{\"name\":\"notification.all.read\",\"data\":[0]}");
        forward("catapult:events:global", "{\"name\":\"alert.info\",\"data\":\"text\"}");
        verifyNoInteractions(events);
    }
}
