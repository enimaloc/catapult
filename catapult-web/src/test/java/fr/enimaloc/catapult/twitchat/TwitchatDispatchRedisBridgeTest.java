package fr.enimaloc.catapult.twitchat;

import fr.enimaloc.catapult.TestRedis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TwitchatDispatchRedisBridgeTest {

    private final TwitchatDispatchHub hub = mock(TwitchatDispatchHub.class);
    private final UUID owner = UUID.randomUUID();

    @Test
    void forwardsADispatch_toItsOwner_asItsDataOnly() {
        TwitchatDispatchRedisBridge.forward(hub, "catapult:events:twitchat:" + owner,
                "{\"name\":\"twitchat.dispatch\",\"data\":{\"id\":\"d-1\",\"payloads\":[]},\"ts\":1}");
        verify(hub).publish(owner, "{\"id\":\"d-1\",\"payloads\":[]}");
    }

    @Test
    void ignoresOtherEvents_otherChannels_andGarbage() {
        TwitchatDispatchRedisBridge.forward(hub, "catapult:events:twitchat:" + owner, "{\"name\":\"twitchat.widget.settings.updated\",\"data\":{}}");
        TwitchatDispatchRedisBridge.forward(hub, "catapult:events:channel:" + owner, "{\"name\":\"twitchat.dispatch\",\"data\":{}}");
        TwitchatDispatchRedisBridge.forward(hub, "catapult:events:twitchat:not-a-uuid", "{\"name\":\"twitchat.dispatch\",\"data\":{}}");
        TwitchatDispatchRedisBridge.forward(hub, "catapult:events:twitchat:" + owner, "{oops");
        verifyNoInteractions(hub);
    }

    private final TwitchatDispatchRedisBridge bridge = new TwitchatDispatchRedisBridge();
    private LettuceConnectionFactory redis;
    private RedisMessageListenerContainer listener;

    /** This instance's listener on {@code redis}, started the way its context would start it. */
    private void listen(LettuceConnectionFactory redis) throws Exception {
        this.redis = redis;
        listener = TestRedis.prepared(bridge.twitchatDispatchListener(redis, hub));
        bridge.startTwitchatDispatchListener(listener).run(new DefaultApplicationArguments());
    }

    @AfterEach
    void stopListening() throws Exception {
        if (listener != null) listener.destroy();
        if (redis != null) redis.destroy();
    }

    @Test
    void throughRedis_aDispatch_reachesItsOwnersPagesOnThisInstance_andNothingElseDoes() throws Exception {
        listen(TestRedis.up());
        StringRedisTemplate publisher = new StringRedisTemplate(redis);

        publisher.convertAndSend(TwitchatDispatchRedisBridge.PREFIX + owner, "{oops");
        publisher.convertAndSend(TwitchatDispatchRedisBridge.PREFIX + "not-a-uuid",
                "{\"name\":\"twitchat.dispatch\",\"data\":{\"id\":\"d-0\"}}");
        publisher.convertAndSend("catapult:events:channel:" + owner, "{\"name\":\"twitchat.dispatch\",\"data\":{\"id\":\"d-0\"}}");
        publisher.convertAndSend(TwitchatDispatchRedisBridge.PREFIX + owner,
                "{\"name\":\"twitchat.dispatch\",\"data\":{\"id\":\"d-1\"},\"ts\":1}");

        verify(hub, timeout(5_000)).publish(owner, "{\"id\":\"d-1\"}");
        verify(hub, after(300).times(1)).publish(any(), anyString());
    }

    @Test
    void aRedisDownAtBoot_costsTheRelay_notTheBoot() {
        redis = TestRedis.down();
        listener = TestRedis.prepared(bridge.twitchatDispatchListener(redis, hub));

        assertThatCode(() -> bridge.startTwitchatDispatchListener(listener).run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
        verifyNoInteractions(hub);
    }
}
