package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ObsProcessCache {

    private final Map<UUID, Set<String>> cache = new ConcurrentHashMap<>();

    public Set<String> getProcesses(UserAccount user) {
        return cache.getOrDefault(user.getId(), Set.of());
    }

    public void update(UserAccount user, Set<String> processes) {
        cache.put(user.getId(), Set.copyOf(processes));
    }

    public void clear(UserAccount user) {
        cache.remove(user.getId());
    }
}
