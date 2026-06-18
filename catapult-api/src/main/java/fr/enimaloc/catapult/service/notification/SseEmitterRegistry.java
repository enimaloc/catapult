package fr.enimaloc.catapult.service.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class SseEmitterRegistry {

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Map<UUID, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter register(UUID userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(err -> remove(userId, emitter));
        return emitter;
    }

    public void pushToUser(UUID userId, String eventName, Object payload) {
        List<SseEmitter> list = emittersByUser.get(userId);
        if (list == null) return;
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException ex) {
                log.debug("SSE push failed, removing emitter for user {}", userId, ex);
                remove(userId, e);
            }
        }
    }

    public int connectionCount(UUID userId) {
        List<SseEmitter> list = emittersByUser.get(userId);
        return list == null ? 0 : list.size();
    }

    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        emittersByUser.forEach((userId, list) -> {
            for (SseEmitter e : list) {
                try {
                    e.send(SseEmitter.event().comment("ping"));
                } catch (IOException ex) {
                    remove(userId, e);
                }
            }
        });
    }

    private void remove(UUID userId, SseEmitter emitter) {
        List<SseEmitter> list = emittersByUser.get(userId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emittersByUser.remove(userId);
            }
        }
    }
}
