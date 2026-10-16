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
 */
@Component
public class WellKnownCache {
    static final Duration TTL = Duration.ofSeconds(60);

    private record Entry(WellKnownContent content, Instant until) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    public WellKnownContent get(WellKnownLocation location, String path, Supplier<WellKnownContent> load) {
        String key = location + ":" + path;
        Instant now = clock.instant();
        Entry entry = entries.get(key);
        if (entry != null && now.isBefore(entry.until())) return entry.content();
        WellKnownContent content = load.get();
        if (content.status() == 200 || content.status() == 404) entries.put(key, new Entry(content, now.plus(TTL)));
        return content;
    }

    public void clear() {
        entries.clear();
    }
}
