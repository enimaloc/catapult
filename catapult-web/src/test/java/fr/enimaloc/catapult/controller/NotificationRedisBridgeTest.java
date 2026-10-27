package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.event.notification.BroadcastStreamEvent;
import fr.enimaloc.catapult.event.notification.NotificationStreamEvent;
import fr.enimaloc.catapult.TestRedis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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

    private final NotificationRedisBridge bridge = new NotificationRedisBridge();
    private LettuceConnectionFactory redis;
    private RedisMessageListenerContainer listener;

    /** This instance's listener on {@code redis}, started the way its context would start it. */
    private void listen(LettuceConnectionFactory redis) throws Exception {
        this.redis = redis;
        listener = TestRedis.prepared(bridge.notificationEventListener(redis, events));
        bridge.startNotificationEventListener(listener).run(new DefaultApplicationArguments());
    }

    @AfterEach
    void stopListening() throws Exception {
        if (listener != null) listener.destroy();
        if (redis != null) redis.destroy();
    }

    @Test
    void throughRedis_aBroadcast_andAnAccountsNotification_reachThisInstancesNavbars() throws Exception {
        listen(TestRedis.up());
        StringRedisTemplate publisher = new StringRedisTemplate(redis);

        publisher.convertAndSend(NotificationRedisBridge.GLOBAL, "{\"name\":\"version.deployed\",\"data\":{\"version\":\"1.2\"}}");
        publisher.convertAndSend(user, "{\"name\":\"notification.all.read\",\"data\":{\"unreadCount\":0}}");

        verify(events, timeout(5_000)).publishEvent(new BroadcastStreamEvent("version.deployed", Map.of("version", "1.2")));
        verify(events, timeout(5_000)).publishEvent(new NotificationStreamEvent(account, NotificationStreamEvent.ALL_READ,
                Map.of("unreadCount", 0)));
    }

    @Test
    void throughRedis_garbage_andTheOtherEventChannels_reachNoNavbar() throws Exception {
        listen(TestRedis.up());
        StringRedisTemplate publisher = new StringRedisTemplate(redis);
        String created = "{\"name\":\"notification.created\",\"data\":{\"id\":\"n1\"}}";

        publisher.convertAndSend(user, "{oops");
        publisher.convertAndSend(NotificationRedisBridge.GLOBAL, "{oops");
        publisher.convertAndSend("catapult:events:admin", "{\"name\":\"alert.info\",\"data\":{}}");
        publisher.convertAndSend("catapult:events:channel:" + account, created);
        publisher.convertAndSend(user, created);

        verify(events, timeout(5_000)).publishEvent(new NotificationStreamEvent(account, NotificationStreamEvent.CREATED,
                Map.of("id", "n1")));
        verify(events, after(300).times(1)).publishEvent(any(Object.class));
    }

    @Test
    void aRedisDownAtBoot_costsTheNotifications_notTheBoot() {
        redis = TestRedis.down();
        listener = TestRedis.prepared(bridge.notificationEventListener(redis, events));

        assertThatCode(() -> bridge.startNotificationEventListener(listener).run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
        verify(events, never()).publishEvent(any(Object.class));
    }
}
