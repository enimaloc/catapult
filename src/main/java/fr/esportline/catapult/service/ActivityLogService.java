package fr.esportline.catapult.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stores a per-user circular buffer of recent activity log entries and
 * broadcasts them to connected SSE clients (dashboard live feed).
 */
@Slf4j
@Service
public class ActivityLogService {

    private static final int MAX_ENTRIES = 50;
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public record LogEntry(Instant timestamp, String level, String message) {
        public String formatted() {
            return "[" + TIME_FMT.format(timestamp) + "] " + level + " — " + message;
        }
    }

    private final Map<UUID, Deque<LogEntry>> buffers = new ConcurrentHashMap<>();
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public void addEntry(UUID userId, String level, String message) {
        LogEntry entry = new LogEntry(Instant.now(), level, message);

        buffers.computeIfAbsent(userId, id -> new ArrayDeque<>());
        Deque<LogEntry> buf = buffers.get(userId);
        synchronized (buf) {
            buf.addLast(entry);
            if (buf.size() > MAX_ENTRIES) buf.removeFirst();
        }

        List<SseEmitter> userEmitters = emitters.getOrDefault(userId, List.of());
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(SseEmitter.event().data(entry.formatted()));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            emitters.getOrDefault(userId, new CopyOnWriteArrayList<>()).removeAll(dead);
        }
    }

    /**
     * Creates and registers a new SSE emitter for the given user.
     * Replays the current buffer so the client gets recent history immediately.
     */
    public SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout

        emitters.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));

        // replay history
        Deque<LogEntry> buf = buffers.getOrDefault(userId, new ArrayDeque<>());
        synchronized (buf) {
            for (LogEntry entry : buf) {
                try {
                    emitter.send(SseEmitter.event().data(entry.formatted()));
                } catch (IOException e) {
                    break;
                }
            }
        }

        return emitter;
    }

    private void remove(UUID userId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(userId);
        if (list != null) list.remove(emitter);
    }
}
