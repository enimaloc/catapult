package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.service.WellKnownContent;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * catapult-api's well-known answers kept 60 s (absences included, not a failure to reach it), so a
 * crawler polling them doesn't reach the API each time; emptied when the admin changes a file.
 * Bounded to {@link #MAX_ENTRIES}: the paths are chosen by whoever asks, so when full, expired entries
 * are dropped and, if it is still full, a new answer is returned without being kept.
 */
@Component
public class WellKnownCache {
    static final Duration TTL = Duration.ofSeconds(60);
    static final int MAX_ENTRIES = 1000;

    private record Entry(WellKnownContent content, Instant until) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    /** Bumped by {@link #clear()}: an answer loaded before it may predate the change and isn't kept. */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

    public WellKnownCache() {
        this(Clock.systemUTC());
    }

    WellKnownCache(Clock clock) {
        this.clock = clock;
    }

    public WellKnownContent get(WellKnownLocation location, String path, Supplier<WellKnownContent> load) {
        String key = location + ":" + path;
        Instant now = clock.instant();
        Entry entry = entries.get(key);
        if (entry != null && now.isBefore(entry.until())) return entry.content();
        long loadedAt = generation.get();
        WellKnownContent content = load.get();
        if ((content.status() == 200 || content.status() == 404) && generation.get() == loadedAt) store(key, new Entry(content, now.plus(TTL)), now);
        return content;
    }

    private void store(String key, Entry entry, Instant now) {
        if (!entries.containsKey(key) && entries.size() >= MAX_ENTRIES) {
            entries.values().removeIf(e -> !now.isBefore(e.until()));
            if (entries.size() >= MAX_ENTRIES) return;
        }
        entries.put(key, entry);
    }

    public void clear() {
        generation.incrementAndGet();
        entries.clear();
    }
}
