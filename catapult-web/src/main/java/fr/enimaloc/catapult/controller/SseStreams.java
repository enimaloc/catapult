package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.cluster.InstanceBus;
import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes the server's SSE streams (dashboards, navbars, Twitchat relays, OBS widgets), so that no
 * caller ever writes to a browser itself:
 * <ul>
 *     <li>off the caller's thread (a Redis listener, the scheduler, a request): each stream has its own
 *     queue, written in order on a virtual thread, so a slow client only holds its own; one that lets more
 *     than {@link #MAX_PENDING} events pile up is closed (its page reconnects and catches up);</li>
 *     <li>with a comment on opening (the response, and so its headers, go out at once) and every
 *     {@link #HEARTBEAT} after, under nginx's read timeout, so that an idle stream is not cut;</li>
 *     <li>closed after their timeout rather than failed (the browser's EventSource reconnects, and
 *     whatever the stream depends on is checked again), and forgotten once closed, whichever way.</li>
 * </ul>
 * It also closes a session's streams on every instance, through the {@link InstanceBus}, when that
 * session logs out or becomes another account (see {@link #closeSession}).
 */
@Slf4j
@Component
public class SseStreams implements DisposableBean {
    /** Below nginx's default 60 s read timeout. */
    static final Duration HEARTBEAT = Duration.ofSeconds(30);
    /** How long a stream stays open; the browser then opens another, its rights checked again. */
    public static final Duration TIMEOUT = Duration.ofMinutes(30);
    static final int MAX_PENDING = 64;
    static final String CLOSE_SESSION_TOPIC = "session.streams.close";
    private static final String HEARTBEAT_COMMENT = "heartbeat";

    private final Executor executor;
    private final Map<SseEmitter, Outbox> open = new ConcurrentHashMap<>();
    private final List<SessionStreams> sessionStreams = new CopyOnWriteArrayList<>();
    private final InteractionMetrics metrics;
    private final InstanceBus bus;

    @Autowired
    public SseStreams(InteractionMetrics metrics, InstanceBus bus) {
        this(metrics, bus, Executors.newVirtualThreadPerTaskExecutor());
    }

    SseStreams(InteractionMetrics metrics, InstanceBus bus, Executor executor) {
        this.metrics = metrics;
        this.bus = bus;
        this.executor = executor;
        bus.on(CLOSE_SESSION_TOPIC, this::closeSessionLocally);
    }

    /** Streams written on the caller's thread, for tests that read what was sent right after sending it. */
    public static SseStreams direct(InteractionMetrics metrics) {
        return new SseStreams(metrics, InstanceBus.local(), Runnable::run);
    }

    /** A stream bounded per session (see {@link SessionStreams}), its sessions closed by {@link #closeSession}. */
    public SessionStreams sessionStreams(String stream) {
        SessionStreams streams = new SessionStreams(stream, metrics);
        sessionStreams.add(streams);
        return streams;
    }

    /** A stream bounded per address (see {@link AnonymousStreams}), for those opened without a session. */
    public AnonymousStreams anonymousStreams(String stream) {
        return new AnonymousStreams(stream, metrics);
    }

    /** A new stream, closed after {@link #TIMEOUT}. */
    public static SseEmitter emitter() {
        return new SseEmitter(TIMEOUT.toMillis());
    }

    /**
     * Writes {@code emitter} from now on: a comment at once, then the events sent to it and the heartbeats.
     * {@code onClosed} runs once when it ends, whichever way (closed, timed out, failed, too slow).
     */
    public void open(SseEmitter emitter, Runnable onClosed) {
        Outbox outbox = new Outbox(emitter, onClosed);
        open.put(emitter, outbox);
        emitter.onCompletion(outbox::closed);
        emitter.onError(e -> outbox.closed());
        // Ended, not failed: a timed-out stream's EventSource reconnects.
        emitter.onTimeout(() -> {
            outbox.closed();
            emitter.complete();
        });
        outbox.offer(SseEmitter.event().comment("open"));
    }

    /** {@code event} to {@code emitter}, after those already sent to it; nothing once it is closed. */
    public void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        Outbox outbox = open.get(emitter);
        if (outbox != null) outbox.offer(event);
    }

    /** {@code name} with {@code data} (as JSON) to {@code emitter}. */
    public void send(SseEmitter emitter, String name, Object data) {
        send(emitter, SseEmitter.event().name(name).data(data));
    }

    /** Ends {@code emitter} once what was sent to it before is written. */
    public void close(SseEmitter emitter) {
        Outbox outbox = open.get(emitter);
        if (outbox == null) {
            emitter.complete();
        } else {
            outbox.offerClose();
        }
    }

    /**
     * Closes {@code sessionId}'s streams on every instance: it logged out, logged in again or now acts as
     * another account, and its pages reconnect as what it is now.
     */
    public void closeSession(String sessionId) {
        if (sessionId != null) bus.publish(CLOSE_SESSION_TOPIC, sessionId);
    }

    private void closeSessionLocally(String sessionId) {
        sessionStreams.forEach(streams -> streams.closeSession(sessionId, this));
    }

    /** A comment to every open stream, so that the proxies on the way keep it. */
    @Scheduled(fixedRateString = "${catapult.sse.heartbeat-millis:30000}", initialDelayString = "${catapult.sse.heartbeat-millis:30000}")
    public void heartbeat() {
        open.keySet().forEach(emitter -> send(emitter, SseEmitter.event().comment(HEARTBEAT_COMMENT)));
    }

    /** How many streams are open; for the tests. */
    int openCount() {
        return open.size();
    }

    @Override
    public void destroy() {
        if (executor instanceof ExecutorService service) service.shutdownNow();
    }

    /** A stream's events not written yet, written in order by one task at a time. */
    private final class Outbox {
        private static final SseEmitter.SseEventBuilder CLOSE = SseEmitter.event();

        private final SseEmitter emitter;
        private final Runnable onClosed;
        private final Queue<SseEmitter.SseEventBuilder> pending = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicBoolean writing = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        Outbox(SseEmitter emitter, Runnable onClosed) {
            this.emitter = emitter;
            this.onClosed = onClosed;
        }

        void offer(SseEmitter.SseEventBuilder event) {
            if (closed.get()) return;
            if (size.incrementAndGet() > MAX_PENDING) {
                log.debug("Closing an SSE stream that does not keep up");
                closed();
                emitter.complete();
                return;
            }
            pending.add(event);
            schedule();
        }

        void offerClose() {
            if (closed.get()) return;
            size.incrementAndGet();
            pending.add(CLOSE);
            schedule();
        }

        private void schedule() {
            if (writing.compareAndSet(false, true)) {
                try {
                    executor.execute(this::write);
                } catch (RuntimeException e) {
                    // shutting down
                    writing.set(false);
                }
            }
        }

        private void write() {
            try {
                SseEmitter.SseEventBuilder event;
                while (!closed.get() && (event = pending.poll()) != null) {
                    size.decrementAndGet();
                    if (event == CLOSE) {
                        closed();
                        emitter.complete();
                        return;
                    }
                    try {
                        emitter.send(event);
                    } catch (IOException | IllegalStateException e) {
                        // Gone, or completed meanwhile.
                        closed();
                        emitter.complete();
                        return;
                    }
                }
            } finally {
                writing.set(false);
            }
            // An event offered after the loop's last poll, before writing was released.
            if (!closed.get() && !pending.isEmpty()) schedule();
        }

        /** Forgotten, once; {@code onClosed} told. */
        void closed() {
            if (closed.compareAndSet(false, true)) {
                open.remove(emitter, this);
                pending.clear();
                onClosed.run();
            }
        }
    }
}
