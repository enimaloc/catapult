package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.ProcessNames;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ObsProcessCache {

    private final Map<UUID, ObsSession> cache = new ConcurrentHashMap<>();

    public ObsSession getSession(UserAccount user) {
        return cache.getOrDefault(user.getId(), ObsSession.EMPTY);
    }

    public void update(UserAccount user, ObsSession session) {
        Set<ObsSession.ProcessSnapshot> normalized = session.processes().stream()
                .map(p -> new ObsSession.ProcessSnapshot(ProcessNames.normalize(p.name()), p.workingDirectory(), p.cmdline()))
                .collect(Collectors.toUnmodifiableSet());
        cache.put(user.getId(), new ObsSession(
                session.os(),
                session.environment() != null ? Map.copyOf(session.environment()) : Map.of(),
                normalized));
    }

    public void clear(UserAccount user) {
        cache.remove(user.getId());
    }
}
