package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class AnonymousStreamsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AnonymousStreams streams = new AnonymousStreams(new InteractionMetrics(registry));

    private double open() {
        return registry.get("catapult.web.sse.streams").tag("stream", "nav-anonymous").gauge().value();
    }

    @Test
    void anIpKeepsAtMostAFewStreams_closingItsOldest() {
        List<SseEmitter> emitters = new ArrayList<>();
        for (int i = 0; i <= AnonymousStreams.MAX_PER_IP; i++) {
            SseEmitter emitter = spy(new SseEmitter());
            emitters.add(emitter);
            assertThat(streams.open("1.2.3.4", emitter)).isTrue();
        }

        verify(emitters.get(0)).complete();
        emitters.subList(1, emitters.size()).forEach(e -> verify(e, never()).complete());
        assertThat(open()).isEqualTo(AnonymousStreams.MAX_PER_IP);
        assertThat(registry.get("catapult.web.sse.evicted").tag("stream", "nav-anonymous").counter().count()).isEqualTo(1);
        assertThat(streams.open("5.6.7.8", new SseEmitter())).isTrue();
        assertThat(open()).isEqualTo(AnonymousStreams.MAX_PER_IP + 1);
    }

    @Test
    void pastTheOverallLimit_streamsAreRefused() {
        for (int i = 0; i < AnonymousStreams.MAX_TOTAL; i++) {
            assertThat(streams.open("10.0." + i / 250 + "." + i % 250, new SseEmitter())).isTrue();
        }

        assertThat(streams.open("9.9.9.9", new SseEmitter())).isFalse();
        assertThat(open()).isEqualTo(AnonymousStreams.MAX_TOTAL);
        assertThat(registry.get("catapult.web.sse.refused").tags("stream", "nav-anonymous", "reason", "capacity")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void aClosedStreamFreesItsSlot() {
        SseEmitter first = new SseEmitter();
        for (int i = 0; i < AnonymousStreams.MAX_TOTAL - 1; i++) {
            streams.open("10.0." + i / 250 + "." + i % 250, new SseEmitter());
        }
        streams.open("1.2.3.4", first);
        assertThat(streams.open("9.9.9.9", new SseEmitter())).isFalse();

        streams.closed("1.2.3.4", first);
        streams.closed("1.2.3.4", first); // twice: counted once
        streams.closed("4.3.2.1", new SseEmitter()); // never opened

        assertThat(open()).isEqualTo(AnonymousStreams.MAX_TOTAL - 1);
        assertThat(streams.open("9.9.9.9", new SseEmitter())).isTrue();
        assertThat(streams.open("9.9.9.8", new SseEmitter())).isFalse();
    }

    @Test
    void anIpv6HostIsBoundByItsSlash64() {
        List<SseEmitter> emitters = new ArrayList<>();
        for (int i = 0; i <= AnonymousStreams.MAX_PER_IP; i++) {
            SseEmitter emitter = spy(new SseEmitter());
            emitters.add(emitter);
            streams.open("2001:db8:1:2:" + Integer.toHexString(i + 1) + "::7", emitter);
        }
        SseEmitter neighbour = spy(new SseEmitter());
        streams.open("2001:db8:1:3::1", neighbour);

        verify(emitters.get(0)).complete();
        emitters.subList(1, emitters.size()).forEach(e -> verify(e, never()).complete());
        verify(neighbour, never()).complete();
        assertThat(open()).isEqualTo(AnonymousStreams.MAX_PER_IP + 1);

        streams.closed("2001:db8:1:2:9::7", emitters.get(8)); // freed under the same /64
        assertThat(open()).isEqualTo(AnonymousStreams.MAX_PER_IP);
    }

    @Test
    void addressKeys() {
        assertThat(AnonymousStreams.addressKey("1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(AnonymousStreams.addressKey("::ffff:1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(AnonymousStreams.addressKey("2001:db8:1:2:3:4:5:6"))
                .isEqualTo(AnonymousStreams.addressKey("2001:0db8:0001:0002:ffff::"))
                .isEqualTo(AnonymousStreams.addressKey("[2001:db8:1:2::1%eth0]"))
                .isNotEqualTo(AnonymousStreams.addressKey("2001:db8:1:3::"));
        assertThat(AnonymousStreams.addressKey("not an address")).isEqualTo("not an address");
        assertThat(AnonymousStreams.addressKey("gg::1")).isEqualTo("gg::1");
    }

    @Test
    void ipv4AddressesStayDistinct() {
        SseEmitter first = spy(new SseEmitter());
        streams.open("1.2.3.4", first);
        for (int i = 0; i < AnonymousStreams.MAX_PER_IP; i++) streams.open("1.2.3." + (10 + i), new SseEmitter());

        verify(first, never()).complete();
    }
}
