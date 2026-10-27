package fr.enimaloc.catapult.cluster;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InstanceBusTest {

    @Test
    void aLoneInstance_handlesItsOwnMessagesAtOnce() {
        InstanceBus bus = InstanceBus.local();
        List<String> got = new ArrayList<>();
        bus.on("topic", got::add);

        bus.publish("topic", "payload");
        bus.publish("other", "ignored");

        assertThat(got).containsExactly("payload");
    }

    @Test
    void withRedis_everyInstanceGetsTheMessage_throughIt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        InstanceBus sender = new InstanceBus(redis);
        InstanceBus receiver = new InstanceBus(redis);
        List<String> got = new ArrayList<>();
        receiver.on("well-known.clear", got::add);
        sender.on("well-known.clear", got::add);

        sender.publish("well-known.clear", "x");
        // Not handled before Redis brings it back, to this instance too.
        assertThat(got).isEmpty();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(InstanceBus.CHANNEL), message.capture());
        receiver.receive(message.getValue());

        assertThat(got).containsExactly("x");
    }

    @Test
    void redisDown_stillReachesThisInstance() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("down")).when(redis).convertAndSend(eq(InstanceBus.CHANNEL), anyString());
        InstanceBus bus = new InstanceBus(redis);
        List<String> got = new ArrayList<>();
        bus.on("topic", got::add);

        bus.publish("topic", "payload");

        assertThat(got).containsExactly("payload");
    }

    @Test
    void garbage_andAFailingHandler_areContained() {
        InstanceBus bus = InstanceBus.local();
        List<String> got = new ArrayList<>();
        bus.on("topic", payload -> {
            throw new IllegalStateException("boom");
        });
        bus.on("topic", got::add);

        bus.receive("{oops");
        bus.receive("{\"topic\":\"topic\"}");
        bus.receive("{\"topic\":\"topic\",\"payload\":\"p\"}");

        assertThat(got).containsExactly("p");
    }
}
