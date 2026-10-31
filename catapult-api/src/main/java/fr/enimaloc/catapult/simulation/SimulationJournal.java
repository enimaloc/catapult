package fr.enimaloc.catapult.simulation;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** The last simulated calls, newest last, and a live stream of the new ones. */
@Slf4j
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulationJournal {

    static final int CAPACITY = 500;

    /**
     * @param at        when the call happened
     * @param accountId the account it acted for, {@code null} for a global call
     * @param key       the {@link CallKey#id()}
     * @param request   a short description of the call
     * @param mode      the {@link CallSetting.Mode} it ran in
     * @param outcome   what came of it ({@code ok}, {@code http 429}, {@code timeout}, the exception of a real
     *                  call that failed)
     * @param latencyMs the latency the setting added before the call
     */
    public record Entry(Instant at, @Nullable UUID accountId, String key, String request, String mode,
                        String outcome, int latencyMs) {}

    private final Deque<Entry> ring = new ArrayDeque<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public void record(Entry entry) {
        synchronized (ring) {
            if (ring.size() == CAPACITY) {
                ring.removeFirst();
            }
            ring.addLast(entry);
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(entry));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    /** The kept entries, oldest first; only the account's own when one is given. */
    public List<Entry> recent(@Nullable UUID accountId) {
        synchronized (ring) {
            return ring.stream().filter(e -> accountId == null || accountId.equals(e.accountId())).toList();
        }
    }

    /** Streams the entries recorded from now on. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }
}
