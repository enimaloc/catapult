package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.cluster.InstanceBus;
import fr.enimaloc.catapult.service.ApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The meta tags the admin configured (Meta tab), written into the head of every page by index.html
 * (see ModelFiller#defaultAttr). catapult-api's list is kept 60 s, so a page doesn't reach it each time,
 * and marked stale when the admin changes a tag. One page at a time loads it, the others meanwhile get
 * the last list known; that list also keeps showing when catapult-api fails, tried again 10 s later.
 */
@Component
public class SiteMetaTags {
    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration RETRY = Duration.ofSeconds(10);

    private record Entry(List<MetaTagDto> tags, Instant until) {
        Entry stale() {
            return new Entry(tags, Instant.MIN);
        }
    }

    static final String CLEAR_TOPIC = "meta-tags.clear";

    private final ApiService apiService;
    private final Clock clock;
    private final InstanceBus bus;
    private final AtomicReference<Entry> entry = new AtomicReference<>(new Entry(List.of(), Instant.MIN));
    private final ReentrantLock loading = new ReentrantLock();
    /** Bumped by {@link #clear()}: a list loaded before it may predate the change and is kept stale. */
    private final AtomicLong generation = new AtomicLong();

    @Autowired
    public SiteMetaTags(ApiService apiService, InstanceBus bus) {
        this(apiService, Clock.systemUTC(), bus);
    }

    SiteMetaTags(ApiService apiService, Clock clock, InstanceBus bus) {
        this.apiService = apiService;
        this.clock = clock;
        this.bus = bus;
        bus.on(CLEAR_TOPIC, ignored -> clearLocally());
    }

    public List<MetaTagDto> tags() {
        Entry current = entry.get();
        if (clock.instant().isBefore(current.until()) || !loading.tryLock()) return current.tags();
        try {
            current = entry.get();
            Instant now = clock.instant();
            if (now.isBefore(current.until())) return current.tags();
            long loadedAt = generation.get();
            List<MetaTagDto> tags = apiService.metaTags();
            Entry loaded = tags == null
                    ? new Entry(current.tags(), now.plus(RETRY))
                    : new Entry(List.copyOf(tags), now.plus(TTL));
            entry.set(loaded);
            // a clear() during the load: this list may predate the change, the next page loads it again
            if (generation.get() != loadedAt) entry.compareAndSet(loaded, loaded.stale());
            return loaded.tags();
        } finally {
            loading.unlock();
        }
    }

    /** Marks the list stale on every instance: the admin's change shows on the next page wherever it is served. */
    public void clear() {
        bus.publish(CLEAR_TOPIC, "");
    }

    void clearLocally() {
        generation.incrementAndGet();
        entry.updateAndGet(Entry::stale);
    }
}
