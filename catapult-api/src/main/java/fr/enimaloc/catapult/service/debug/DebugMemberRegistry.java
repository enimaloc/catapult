package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.MemberDebugSession;
import fr.enimaloc.catapult.repository.debug.MemberDebugSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory view of the members currently in debug mode, so that the hot path (every outbound HTTP call) costs one
 * map lookup (lock-free). Loaded at startup, kept up to date by {@link MemberDebugService}.
 * Writers are serialized, load() reading the DB under the lock, so none of them can be lost or undone by another.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DebugMemberRegistry {

    public record DebugMode(UUID userId, boolean revealSecrets) {}

    private final MemberDebugSessionRepository sessions;
    private final Map<UUID, DebugMode> modes = new ConcurrentHashMap<>();

    public Optional<DebugMode> modeOf(UUID userId) {
        return userId == null ? Optional.empty() : Optional.ofNullable(modes.get(userId));
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void load() {
        Map<UUID, DebugMode> loaded = new ConcurrentHashMap<>();
        for (MemberDebugSession s : sessions.findByActiveTrue()) {
            loaded.put(s.getUserId(), new DebugMode(s.getUserId(), s.isRevealSecrets()));
        }
        modes.keySet().retainAll(loaded.keySet());
        modes.putAll(loaded);
        log.info("Debug mode active for {} member(s)", loaded.size());
    }

    synchronized void activate(UUID userId, boolean revealSecrets) {
        modes.put(userId, new DebugMode(userId, revealSecrets));
    }

    synchronized void deactivate(UUID userId) {
        modes.remove(userId);
    }
}
