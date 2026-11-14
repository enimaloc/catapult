package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The gated platforms the landing page lists (see SiteCatalog#platforms): Battle.net and Ankama, once their experiment
 * is fully enabled. catapult-api's answer is kept 60 s, so a page doesn't reach it each time; one page at a time
 * loads it, the others meanwhile get the last answer, which also keeps showing when catapult-api fails, tried again
 * 10 s later. Until an answer was ever obtained, none shows: a gated platform is never advertised by mistake.
 */
@Component
public class LaunchedPlatforms {
    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration RETRY = Duration.ofSeconds(10);

    private record Entry(List<String> ids, Instant until) {}

    private final ApiService apiService;
    private final Clock clock;
    private final AtomicReference<Entry> entry = new AtomicReference<>(new Entry(List.of(), Instant.MIN));
    private final ReentrantLock loading = new ReentrantLock();

    @Autowired
    public LaunchedPlatforms(ApiService apiService) {
        this(apiService, Clock.systemUTC());
    }

    LaunchedPlatforms(ApiService apiService, Clock clock) {
        this.apiService = apiService;
        this.clock = clock;
    }

    public List<String> ids() {
        Entry current = entry.get();
        if (clock.instant().isBefore(current.until()) || !loading.tryLock()) return current.ids();
        try {
            current = entry.get();
            Instant now = clock.instant();
            if (now.isBefore(current.until())) return current.ids();
            List<String> ids = apiService.launchedPlatforms();
            Entry loaded = ids == null
                    ? new Entry(current.ids(), now.plus(RETRY))
                    : new Entry(List.copyOf(ids), now.plus(TTL));
            entry.set(loaded);
            return loaded.ids();
        } finally {
            loading.unlock();
        }
    }
}
