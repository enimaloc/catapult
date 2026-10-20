package fr.enimaloc.catapult.cluster;

import fr.enimaloc.catapult.TestRedis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Instances that only share a real Redis tell each other what {@link InstanceBus} carries. */
class InstanceBusRedisBridgeTest {

    private final InstanceBusRedisBridge bridge = new InstanceBusRedisBridge();
    private final List<RedisMessageListenerContainer> listeners = new ArrayList<>();
    private final List<LettuceConnectionFactory> connections = new ArrayList<>();
    /** Each test's own topic: the Redis is shared by the whole run. */
    private final String topic = "test." + UUID.randomUUID();

    /** A catapult-web instance on {@code redis}: its bus, and the listener its context would start. */
    private InstanceBus instance(LettuceConnectionFactory redis) throws Exception {
        connections.add(redis);
        InstanceBus bus = new InstanceBus(new StringRedisTemplate(redis));
        RedisMessageListenerContainer listener = TestRedis.prepared(bridge.instanceBusListener(redis, bus));
        listeners.add(listener);
        bridge.startInstanceBusListener(listener).run(new DefaultApplicationArguments());
        return bus;
    }

    private BlockingQueue<String> received(InstanceBus bus) {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        bus.on(topic, received::add);
        return received;
    }

    @AfterEach
    void stop() throws Exception {
        for (RedisMessageListenerContainer listener : listeners) listener.destroy();
        connections.forEach(LettuceConnectionFactory::destroy);
    }

    @Test
    void aMessage_reachesEveryInstance_itsSenderIncluded_once() throws Exception {
        InstanceBus sender = instance(TestRedis.up());
        InstanceBus other = instance(TestRedis.up());
        BlockingQueue<String> bySender = received(sender);
        BlockingQueue<String> byOther = received(other);

        sender.publish(topic, "payload");

        assertThat(bySender.poll(5, TimeUnit.SECONDS)).isEqualTo("payload");
        assertThat(byOther.poll(5, TimeUnit.SECONDS)).isEqualTo("payload");
        assertThat(bySender.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(byOther).isEmpty();
    }

    @Test
    void aMalformedMessageOnTheChannel_isDropped_andTheNextOnesStillGetThrough() throws Exception {
        LettuceConnectionFactory redis = TestRedis.up();
        InstanceBus bus = instance(redis);
        BlockingQueue<String> received = received(bus);
        StringRedisTemplate raw = new StringRedisTemplate(redis);

        raw.convertAndSend(InstanceBus.CHANNEL, "{oops");
        raw.convertAndSend(InstanceBus.CHANNEL, "{\"topic\":\"" + topic + "\"}");
        bus.publish(topic, "after");

        assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo("after");
        assertThat(received).isEmpty();
    }

    @Test
    void anotherTopicsMessage_reachesNoneOfThisTopicsHandlers() throws Exception {
        InstanceBus sender = instance(TestRedis.up());
        InstanceBus other = instance(TestRedis.up());
        BlockingQueue<String> received = received(other);

        sender.publish(topic + ".other", "not for it");
        sender.publish(topic, "for it");

        assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo("for it");
        assertThat(received).isEmpty();
    }

    @Test
    void aRedisDownAtBoot_neitherFailsTheBoot_norKeepsThisInstanceFromItsOwnMessages() throws Exception {
        LettuceConnectionFactory redis = TestRedis.down();
        connections.add(redis);
        InstanceBus bus = new InstanceBus(new StringRedisTemplate(redis));
        RedisMessageListenerContainer listener = TestRedis.prepared(bridge.instanceBusListener(redis, bus));
        listeners.add(listener);
        BlockingQueue<String> received = received(bus);

        assertThatCode(() -> bridge.startInstanceBusListener(listener).run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
        bus.publish(topic, "payload");

        assertThat(received).containsExactly("payload");
    }
}
