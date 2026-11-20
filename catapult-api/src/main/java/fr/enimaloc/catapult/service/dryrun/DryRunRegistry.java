package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.dryrun.MemberDryRun;
import fr.enimaloc.catapult.repository.dryrun.MemberDryRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory view of the members in dry run, so that the hot path (every write to Twitch) costs one set lookup
 * (lock-free). Loaded at startup, kept up to date by {@link DryRunService} on this instance, and reloaded from the
 * database every {@value #REFRESH_MS} ms for what another instance (or the other site) changed.
 * Writers are serialized, load() reading the DB under the lock, so none of them can be lost or undone by another.
 * Each member leaving the set, either way, is announced once as a {@link DryRunEndedEvent}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DryRunRegistry {

    static final long REFRESH_MS = 30_000;

    private final MemberDryRunRepository dryRuns;
    private final ApplicationEventPublisher events;
    private final Set<UUID> members = ConcurrentHashMap.newKeySet();

    public boolean isActive(UUID userId) {
        return userId != null && members.contains(userId);
    }

    /** The members in dry run right now. */
    public Set<UUID> members() {
        return Set.copyOf(members);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void load() {
        Set<UUID> loaded = new HashSet<>();
        for (MemberDryRun row : dryRuns.findAll()) {
            loaded.add(row.getUserId());
        }
        Set<UUID> left = new HashSet<>(members);
        left.removeAll(loaded);
        members.retainAll(loaded);
        members.addAll(loaded);
        left.forEach(this::announceEnd);
        log.debug("Dry run active for {} member(s)", loaded.size());
    }

    @Scheduled(fixedDelay = REFRESH_MS, initialDelay = REFRESH_MS)
    public void refresh() {
        load();
    }

    synchronized void activate(UUID userId) {
        members.add(userId);
    }

    /** Never throws: what listens to the end of a dry run is guarded. */
    synchronized void deactivate(UUID userId) {
        if (members.remove(userId)) {
            announceEnd(userId);
        }
    }

    private void announceEnd(UUID userId) {
        try {
            events.publishEvent(new DryRunEndedEvent(userId));
        } catch (RuntimeException e) {
            log.warn("Could not handle the end of the dry run of {}: {}", userId, e.toString());
        }
    }
}
