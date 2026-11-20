package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.common.dto.DryRunStateDto;
import fr.enimaloc.catapult.service.ApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether the logged-in member is in dry run, as catapult-api answers, remembered for {@link #TTL} so a page render
 * (the shell's banner) does not call it every time. A member switched through this instance, by an admin or by
 * themself, is {@link #evict forgotten} at once. A failed call counts as "not in dry run" (the page must render) and is
 * remembered only for {@link #FAILURE_TTL}, so a down catapult-api is not hammered.
 */
@Component
public class DryRunMembers {
    static final Duration TTL = Duration.ofSeconds(30);
    static final Duration FAILURE_TTL = Duration.ofSeconds(5);
    private static final Logger log = LoggerFactory.getLogger(DryRunMembers.class);

    private record Entry(boolean active, Instant expiresAt) {}

    // Lazy, as DebugMembers's: ApiService is not needed until a page asks.
    private final ObjectProvider<ApiService> apiService;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public DryRunMembers(ObjectProvider<ApiService> apiService) {
        this(apiService, Clock.systemUTC());
    }

    DryRunMembers(ObjectProvider<ApiService> apiService, Clock clock) {
        this.apiService = apiService;
        this.clock = clock;
    }

    /** Whether the member of the request being served is in dry run; false when nobody is, or on any failure. */
    public boolean isCurrentActive() {
        try {
            ApiService api = apiService.getIfAvailable();
            UUID member = api == null ? null : api.currentUserId();
            return member != null && lookup(api, member);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean lookup(ApiService api, UUID member) {
        Instant now = clock.instant();
        Entry cached = cache.get(member);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.active();
        }
        Entry fresh;
        try {
            DryRunStateDto state = api.myDryRun();
            // null: catapult-api could not tell, which is remembered only briefly
            fresh = state == null ? new Entry(false, now.plus(FAILURE_TTL)) : new Entry(state.active(), now.plus(TTL));
        } catch (RuntimeException e) {
            log.debug("Could not tell whether {} is in dry run: {}", member, e.toString());
            fresh = new Entry(false, now.plus(FAILURE_TTL));
        }
        cache.put(member, fresh);
        return fresh.active();
    }

    /** Forgets what is known of {@code member}: their next page asks catapult-api again. */
    public void evict(UUID member) {
        cache.remove(member);
    }
}
