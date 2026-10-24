package fr.enimaloc.catapult.service.debug;

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
 * Whether a member is in debug mode, as catapult-api answers for the logged-in member, remembered for
 * {@link #TTL} so a page render does not call it every time. A member the admin toggles through this
 * instance is {@link #evict forgotten} at once. A failed call counts as "not debugged" (the page
 * must render) and is remembered only for {@link #FAILURE_TTL}, so a down catapult-api is not hammered.
 */
@Component
public class DebugMembers {
    static final Duration TTL = Duration.ofSeconds(30);
    static final Duration FAILURE_TTL = Duration.ofSeconds(5);
    private static final Logger log = LoggerFactory.getLogger(DebugMembers.class);

    private record Entry(boolean active, Instant expiresAt) {}

    private final ObjectProvider<ApiService> apiService;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public DebugMembers(ObjectProvider<ApiService> apiService) {
        this(apiService, Clock.systemUTC());
    }

    DebugMembers(ObjectProvider<ApiService> apiService, Clock clock) {
        this.apiService = apiService;
        this.clock = clock;
    }

    /** Whether {@code member}, the logged-in one, is in debug mode. */
    public boolean isActive(UUID member) {
        if (member == null) return false;
        Instant now = clock.instant();
        Entry cached = cache.get(member);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.active();
        }
        Entry fresh;
        try {
            ApiService api = apiService.getIfAvailable();
            Boolean active = api == null ? null : api.myDebugActive();
            // null: catapult-api could not tell, which is remembered only briefly
            fresh = active == null ? new Entry(false, now.plus(FAILURE_TTL)) : new Entry(active, now.plus(TTL));
        } catch (RuntimeException e) {
            log.debug("Could not tell whether {} is in debug mode: {}", member, e.toString());
            fresh = new Entry(false, now.plus(FAILURE_TTL));
        }
        cache.put(member, fresh);
        return fresh.active();
    }

    /** Whether the member of the request being served is in debug mode; false when nobody is, or on any failure. */
    public boolean isCurrentActive() {
        try {
            ApiService api = apiService.getIfAvailable();
            return api != null && isActive(api.currentUserId());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Forgets what is known of {@code member}: the next page asks catapult-api again. */
    public void evict(UUID member) {
        cache.remove(member);
    }
}
