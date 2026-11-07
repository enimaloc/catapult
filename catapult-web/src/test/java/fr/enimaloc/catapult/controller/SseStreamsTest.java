package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.cluster.InstanceBus;
import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SseStreamsTest {

    private final InteractionMetrics metrics = new InteractionMetrics(new SimpleMeterRegistry());
    private final AtomicInteger closed = new AtomicInteger();

    @Test
    void writesAtOnce_thenEachEventInOrder_andHeartbeats() throws IOException {
        SseStreams sse = SseStreams.direct(metrics);
        SseEmitter emitter = mock(SseEmitter.class);

        sse.open(emitter, closed::incrementAndGet);
        sse.send(emitter, "A", "1");
        sse.heartbeat();

        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(sse.openCount()).isEqualTo(1);
    }

    @Test
    void writesOffTheCallersThread_oneTaskAtATime() throws IOException {
        List<Runnable> tasks = new ArrayList<>();
        SseStreams sse = new SseStreams(metrics, InstanceBus.local(), tasks::add);
        SseEmitter emitter = mock(SseEmitter.class);
        sse.open(emitter, closed::incrementAndGet);
        clearInvocations(emitter);

        sse.send(emitter, "A", "1");
        sse.send(emitter, "B", "2");

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(tasks).hasSize(1);
        tasks.getFirst().run();
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    /*
     * The opening comment is handed to the emitter on the opening request's thread, before the
     * controller returns it: Spring then writes it, and so commits the response, on that thread
     * (ResponseBodyEmitter#initialize). Written by the outbox's thread instead, it could commit the
     * response while the request's filters still set headers on it (Spring Security's
     * HeaderWriterFilter), and Tomcat's headers, not thread-safe, broke the stream (a 500, its EOF).
     */
    @Test
    void theOpeningComment_isSentOnTheCallersThread() throws IOException {
        List<Runnable> tasks = new ArrayList<>();
        SseStreams sse = new SseStreams(metrics, InstanceBus.local(), tasks::add);
        SseEmitter emitter = mock(SseEmitter.class);

        sse.open(emitter, closed::incrementAndGet);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(tasks).isEmpty();
        assertThat(sse.openCount()).isEqualTo(1);
    }

    /* Registered only once the comment is sent: no heartbeat or other thread's event can come before it. */
    @Test
    void aStream_isRegisteredOnlyAfterItsOpeningComment() throws IOException {
        List<Runnable> tasks = new ArrayList<>();
        SseStreams sse = new SseStreams(metrics, InstanceBus.local(), tasks::add);
        SseEmitter emitter = mock(SseEmitter.class);
        List<Integer> openWhenSent = new ArrayList<>();
        doAnswer(invocation -> openWhenSent.add(sse.openCount()))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        sse.open(emitter, closed::incrementAndGet);

        assertThat(openWhenSent).containsExactly(0);
        assertThat(sse.openCount()).isEqualTo(1);
    }

    @Test
    void aStreamThatFailsOnOpening_isClosedAndForgotten() throws IOException {
        SseStreams sse = new SseStreams(metrics, InstanceBus.local(), Runnable::run);
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("completed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        sse.open(emitter, closed::incrementAndGet);
        sse.send(emitter, "A", "1");

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        assertThat(closed).hasValue(1);
        assertThat(sse.openCount()).isZero();
    }

    @Test
    void aStreamThatFails_isClosedAndForgotten_once() throws IOException {
        SseStreams sse = SseStreams.direct(metrics);
        SseEmitter emitter = mock(SseEmitter.class);
        sse.open(emitter, closed::incrementAndGet);
        doThrow(new IOException("gone")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        sse.send(emitter, "A", "1");
        sse.send(emitter, "B", "2");

        verify(emitter).complete();
        assertThat(closed).hasValue(1);
        assertThat(sse.openCount()).isZero();
    }

    @Test
    void aStreamThatDoesNotKeepUp_isClosed() {
        List<Runnable> never = new ArrayList<>();
        SseStreams sse = new SseStreams(metrics, InstanceBus.local(), never::add);
        SseEmitter emitter = mock(SseEmitter.class);
        sse.open(emitter, closed::incrementAndGet);

        for (int i = 0; i <= SseStreams.MAX_PENDING; i++) sse.send(emitter, "A", i);

        verify(emitter).complete();
        assertThat(closed).hasValue(1);
    }

    @Test
    void close_endsTheStreamAfterWhatWasSentBefore() throws IOException {
        SseStreams sse = SseStreams.direct(metrics);
        SseEmitter emitter = mock(SseEmitter.class);
        sse.open(emitter, closed::incrementAndGet);

        sse.send(emitter, "A", "1");
        sse.close(emitter);

        InOrder order = inOrder(emitter);
        order.verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        order.verify(emitter).complete();
        assertThat(closed).hasValue(1);
    }

    @Test
    void closeSession_closesThatSessionsStreams_onEveryInstance() {
        InstanceBus bus = InstanceBus.local();
        SseStreams here = new SseStreams(metrics, bus, Runnable::run);
        SseStreams there = new SseStreams(metrics, bus, Runnable::run);
        SessionStreams streams = there.sessionStreams("channel");
        SseEmitter mine = mock(SseEmitter.class);
        SseEmitter other = mock(SseEmitter.class);
        there.open(mine, closed::incrementAndGet);
        there.open(other, closed::incrementAndGet);
        streams.open("s1", mine);
        streams.open("s2", other);

        here.closeSession("s1");

        verify(mine).complete();
        verify(other, never()).complete();
    }
}
