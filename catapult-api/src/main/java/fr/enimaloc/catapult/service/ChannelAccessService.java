package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelAccessService {

    private static final Duration TTL = Duration.ofMinutes(15);

    private final TwitchService twitchService;
    private final UserAccountRepository userAccountRepository;
    private final Map<UUID, CachedModStatus> cache = new ConcurrentHashMap<>();

    public boolean canAccess(UserAccount viewer, UserAccount channel) {
        if (viewer.getId().equals(channel.getId())) return true;
        return fetchModeratedIds(viewer).contains(channel.getTwitchId());
    }

    public List<UserAccount> getAccessibleChannels(UserAccount viewer) {
        Set<String> moderatedIds = fetchModeratedIds(viewer);
        List<UserAccount> result = new ArrayList<>();
        result.add(viewer);
        userAccountRepository.findByTwitchIdIn(moderatedIds).stream()
            .filter(u -> !u.getId().equals(viewer.getId()))
            .forEach(result::add);
        return result;
    }

    private Set<String> fetchModeratedIds(UserAccount viewer) {
        CachedModStatus cached = cache.get(viewer.getId());
        if (cached != null && !cached.isExpired()) return cached.moderatedIds();
        CachedModStatus fresh = cache.compute(viewer.getId(), (id, existing) -> {
            if (existing != null && !existing.isExpired()) return existing;
            try {
                Set<String> ids = new HashSet<>(twitchService.getModeratedChannelIds(viewer));
                return new CachedModStatus(ids, Instant.now().plus(TTL));
            } catch (Exception e) {
                log.warn("Failed to fetch moderated channels for user {} — denying access", id, e);
                return existing;
            }
        });
        return fresh != null ? fresh.moderatedIds() : Set.of();
    }

    private record CachedModStatus(Set<String> moderatedIds, Instant expiresAt) {
        CachedModStatus {
            moderatedIds = Collections.unmodifiableSet(moderatedIds);
        }
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    }
}
