package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-user activity feed: a bounded history replayed to, and pushed live to, SSE clients. */
class ActivityLogServiceTest {

    private final ActivityLogService service = new ActivityLogService();
    private final UUID user = UUID.randomUUID();

    /** Records what it is sent, or fails every send once {@code broken}. */
    private static final class RecordingEmitter extends SseEmitter {
        final List<Set<?>> sent = new ArrayList<>();
        boolean broken;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (broken) throw new IOException("client gone");
            sent.add(builder.build());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, List<SseEmitter>> emitters() {
        return (Map<UUID, List<SseEmitter>>) ReflectionTestUtils.getField(service, "emitters");
    }

    @SuppressWarnings("unchecked")
    private Deque<ActivityLogService.LogEntry> history() {
        return ((Map<UUID, Deque<ActivityLogService.LogEntry>>) ReflectionTestUtils.getField(service, "buffers")).get(user);
    }

    @Test
    void entriesAreKept_upToTheLastFifty() {
        for (int i = 0; i < 55; i++) service.addEntry(user, "INFO", "event " + i);

        assertThat(history()).hasSize(50);
        assertThat(history().getFirst().message()).isEqualTo("event 5");
        assertThat(history().getLast().level()).isEqualTo("INFO");
    }

    @Test
    void entries_arePushedToLiveClients_andDeadOnesDropped() {
        RecordingEmitter alive = new RecordingEmitter();
        RecordingEmitter dead = new RecordingEmitter();
        dead.broken = true;
        emitters().put(user, new CopyOnWriteArrayList<>(List.of(alive, dead)));

        service.addEntry(user, "WARN", "rate limited");

        assertThat(alive.sent).hasSize(1);
        assertThat(emitters().get(user)).containsExactly(alive);
    }

    @Test
    void subscribing_registersTheClient_untilItCompletes() {
        service.addEntry(user, "INFO", "before");

        SseEmitter emitter = service.subscribe(user);

        assertThat(emitter.getTimeout()).isZero();
        assertThat(emitters().get(user)).containsExactly(emitter);
        service.addEntry(user, "INFO", "after");
        assertThat(history()).hasSize(2);
    }

    @Test
    void subscribing_withoutHistory_works() {
        assertThat(service.subscribe(UUID.randomUUID())).isNotNull();
    }

    @Test
    void entries_formatAsTimeLevelAndMessage() {
        ActivityLogService.LogEntry entry = new ActivityLogService.LogEntry(Instant.parse("2026-01-01T10:15:30Z"), "INFO", "hello");

        assertThat(entry.formatted()).matches("\\[\\d{2}:15:30] INFO — hello");
    }
}
