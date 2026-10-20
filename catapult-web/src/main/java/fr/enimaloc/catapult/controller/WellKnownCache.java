package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.cluster.InstanceBus;
import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.service.WellKnownContent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * catapult-api's well-known answers kept 60 s (absences included, not a failure to reach it), so a
 * crawler polling them doesn't reach the API each time; emptied when the admin changes a file, on every
 * catapult-web instance (through the {@link InstanceBus}).
 * Bounded to {@link #MAX_ENTRIES}: the paths are chosen by whoever asks, so when full, expired entries
 * are dropped and, if it is still full, a new answer is returned without being kept.
 */
@Component
public class WellKnownCache {
    static final Duration TTL = Duration.ofSeconds(60);
    static final int MAX_ENTRIES = 1000;
    static final String CLEAR_TOPIC = "well-known.clear";

    private record Entry(WellKnownContent content, Instant until) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    /** Bumped by {@link #clear()}: an answer loaded before it may predate the change and isn't kept. */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

    /** Null for a cache alone (a test): its clear only empties itself. */
    private final InstanceBus bus;

    @Autowired
    public WellKnownCache(ObjectProvider<InstanceBus> bus) {
        this(Clock.systemUTC(), bus.getIfAvailable());
    }

    WellKnownCache(Clock clock) {
        this(clock, null);
    }

    WellKnownCache(Clock clock, InstanceBus bus) {
        this.clock = clock;
        this.bus = bus;
        if (bus != null) bus.on(CLEAR_TOPIC, ignored -> clearLocally());
    }

    public WellKnownContent get(WellKnownLocation location, String path, Supplier<WellKnownContent> load) {
        String key = location + ":" + path;
        Instant now = clock.instant();
        Entry entry = entries.get(key);
        if (entry != null && now.isBefore(entry.until())) return entry.content();
        long loadedAt = generation.get();
        WellKnownContent content = load.get();
        if (content.status() == 200 || content.status() == 404) store(key, new Entry(content, now.plus(TTL)), now, loadedAt);
        return content;
    }

    /**
     * Kept unless a {@link #clear()} came since it was loaded. Checked once it is in: a clear before
     * the check is seen by it, one after empties the map itself, so no answer outlives the change.
     */
    private void store(String key, Entry entry, Instant now, long loadedAt) {
        if (!entries.containsKey(key) && entries.size() >= MAX_ENTRIES) {
            entries.values().removeIf(e -> !now.isBefore(e.until()));
            if (entries.size() >= MAX_ENTRIES) return;
        }
        entries.put(key, entry);
        if (generation.get() != loadedAt) entries.remove(key, entry);
    }

    /** Empties this cache on every instance: the admin's change shows wherever the next crawler lands. */
    public void clear() {
        if (bus == null) {
            clearLocally();
        } else {
            bus.publish(CLEAR_TOPIC, "");
        }
    }

    void clearLocally() {
        generation.incrementAndGet();
        entries.clear();
    }
}
