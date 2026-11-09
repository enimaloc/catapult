package fr.enimaloc.catapult.twitchat;

import fr.enimaloc.catapult.TestRedis;
import fr.enimaloc.catapult.common.event.ChannelEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationDeliveryRedisBridgeTest {

    private final TwitchatDispatchHub hub = mock(TwitchatDispatchHub.class);
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final NotificationDeliveryRedisBridge bridge = new NotificationDeliveryRedisBridge();
    private LettuceConnectionFactory redis;
    private RedisMessageListenerContainer listener;

    private static String pending(UUID... ids) {
        StringBuilder users = new StringBuilder();
        for (UUID id : ids) users.append(users.isEmpty() ? "" : ",").append('"').append(id).append('"');
        return "{\"name\":\"notification.delivery.pending\",\"data\":{\"userIds\":[" + users + "]},\"ts\":1}";
    }

    @AfterEach
    void stopListening() throws Exception {
        if (listener != null) listener.destroy();
        if (redis != null) redis.destroy();
    }

    @Test
    void listensOnTheDeliveryChannel_notAChannelPattern() {
        assertThat(NotificationDeliveryRedisBridge.CHANNEL).isEqualTo(ChannelEvents.NOTIFICATION_DELIVERY_REDIS_CHANNEL);
    }

    @Test
    void everyNamedMember_isAskedToFlush_onThisInstance() {
        NotificationDeliveryRedisBridge.forward(hub, NotificationDeliveryRedisBridge.CHANNEL, pending(first, second));

        verify(hub).flush(first);
        verify(hub).flush(second);
    }

    @Test
    void ignoresOtherEvents_otherChannels_andGarbage() {
        NotificationDeliveryRedisBridge.forward(hub, NotificationDeliveryRedisBridge.CHANNEL, "{\"name\":\"other\",\"data\":{\"userIds\":[\"" + first + "\"]}}");
        NotificationDeliveryRedisBridge.forward(hub, "catapult:events:channel:" + first, pending(first));
        NotificationDeliveryRedisBridge.forward(hub, NotificationDeliveryRedisBridge.CHANNEL, "{oops");
        NotificationDeliveryRedisBridge.forward(hub, NotificationDeliveryRedisBridge.CHANNEL, "{\"name\":\"notification.delivery.pending\",\"data\":null}");
        NotificationDeliveryRedisBridge.forward(hub, NotificationDeliveryRedisBridge.CHANNEL, "{\"name\":\"notification.delivery.pending\",\"data\":{\"userIds\":\"x\"}}");

        verifyNoInteractions(hub);
    }

    @Test
    void aMalformedId_doesNotStopTheOthers() {
        NotificationDeliveryRedisBridge.forward(hub, NotificationDeliveryRedisBridge.CHANNEL,
                "{\"name\":\"notification.delivery.pending\",\"data\":{\"userIds\":[\"nope\",\"" + second + "\"]}}");

        verify(hub).flush(second);
        verify(hub, times(1)).flush(any());
    }

    @Test
    void throughRedis_aPendingEvent_flushesItsMembers() throws Exception {
        redis = TestRedis.up();
        listener = TestRedis.prepared(bridge.notificationDeliveryListener(redis, hub));
        bridge.startNotificationDeliveryListener(listener).run(new DefaultApplicationArguments());
        StringRedisTemplate publisher = new StringRedisTemplate(redis);

        publisher.convertAndSend("catapult:events:channel:" + first, pending(first));
        publisher.convertAndSend(NotificationDeliveryRedisBridge.CHANNEL, pending(second));

        verify(hub, timeout(5_000)).flush(second);
        verify(hub, after(300).never()).flush(first);
    }

    @Test
    void aRedisDownAtBoot_costsTheRelay_notTheBoot() {
        redis = TestRedis.down();
        listener = TestRedis.prepared(bridge.notificationDeliveryListener(redis, hub));

        assertThatCode(() -> bridge.startNotificationDeliveryListener(listener).run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
        verify(hub, never()).flush(any());
    }
}
