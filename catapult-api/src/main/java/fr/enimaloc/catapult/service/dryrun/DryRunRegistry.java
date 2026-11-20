package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.dryrun.MemberDryRun;
import fr.enimaloc.catapult.repository.dryrun.MemberDryRunRepository;
import jakarta.annotation.PostConstruct;
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
import java.util.function.LongSupplier;

/**
 * In-memory view of the members in dry run, so that the hot path (every write to Twitch) costs one set lookup
 * (lock-free). Loaded as soon as the bean is built, before anything can ask it, kept up to date by
 * {@link DryRunService} on this instance, and reloaded from the database every {@value #REFRESH_MS} ms for what
 * another instance (or the other site) changed. Until a first load succeeds, who is in dry run is unknown
 * ({@link #isLoaded}): the gate then holds every write, and the load is retried at most every {@value #RETRY_MS} ms.
 * Writers are serialized, load() reading the DB under the lock, so none of them can be lost or undone by another.
 * Each member leaving the set, either way, is announced once as a {@link DryRunEndedEvent}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DryRunRegistry {

    static final long REFRESH_MS = 30_000;
    static final long RETRY_MS = 5_000;

    private final MemberDryRunRepository dryRuns;
    private final ApplicationEventPublisher events;
    private final Set<UUID> members = ConcurrentHashMap.newKeySet();
    /** Whether a load ever succeeded: before that, an empty set means "unknown", not "nobody". */
    private volatile boolean loaded;
    /** When the next retry of a failed first load may run, in {@link #clock} milliseconds. */
    private volatile long nextRetryAt;
    /** Tells the time in milliseconds (tests swap in their own). */
    LongSupplier clock = System::currentTimeMillis;

    /** Whether {@code userId} is known to be in dry run; false too while the set is not loaded (see {@link #isLoaded}). */
    public boolean isActive(UUID userId) {
        return userId != null && members.contains(userId);
    }

    /** The members in dry run right now. */
    public Set<UUID> members() {
        return Set.copyOf(members);
    }

    /**
     * Whether the set has been read from the database at least once, so that {@link #isActive} can be trusted. While
     * it has not, retries the load, at most every {@value #RETRY_MS} ms; never throws.
     */
    public boolean isLoaded() {
        if (!loaded && clock.getAsLong() >= nextRetryAt) {
            tryLoad();
        }
        return loaded;
    }

    /** Loads the set at once, before any consumer of the gate can write to Twitch; a failure is retried later. */
    @PostConstruct
    void init() {
        tryLoad();
    }

    private void tryLoad() {
        try {
            load();
        } catch (RuntimeException e) {
            nextRetryAt = clock.getAsLong() + RETRY_MS;
            log.warn("Could not load who is in dry run, every Twitch write is held until it is: {}", e.toString());
        }
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
        this.loaded = true;
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
