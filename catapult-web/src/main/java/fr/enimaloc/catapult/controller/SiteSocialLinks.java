package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.SocialLinkDto;
import fr.enimaloc.catapult.common.dto.admin.SocialPlatform;
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
 * The social links the admin configured, shown in the footer of every page (see ModelFiller#defaultAttr).
 * catapult-api's list is kept 60 s, so a page doesn't reach it each time, and marked stale when the admin
 * changes a link. One page at a time loads it, the others meanwhile get the last list known; that list
 * also keeps showing when catapult-api fails, tried again 10 s later. Until a list was ever obtained, the
 * footer shows the project's GitHub link, so it is never empty.
 */
@Component
public class SiteSocialLinks {
    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration RETRY = Duration.ofSeconds(10);

    private record Entry(List<SocialLinkDto> links, Instant until) {
        Entry stale() {
            return new Entry(links, Instant.MIN);
        }
    }

    static final List<SocialLinkDto> FALLBACK = List.of(new SocialLinkDto(0, SocialPlatform.GITHUB, null,
            "https://github.com/enimaloc/catapult", true, 0));

    static final String CLEAR_TOPIC = "social-links.clear";

    private final ApiService apiService;
    private final Clock clock;
    private final InstanceBus bus;
    private final AtomicReference<Entry> entry = new AtomicReference<>(new Entry(FALLBACK, Instant.MIN));
    private final ReentrantLock loading = new ReentrantLock();
    /** Bumped by {@link #clear()}: a list loaded before it may predate the change and is kept stale. */
    private final AtomicLong generation = new AtomicLong();

    @Autowired
    public SiteSocialLinks(ApiService apiService, InstanceBus bus) {
        this(apiService, Clock.systemUTC(), bus);
    }

    SiteSocialLinks(ApiService apiService, Clock clock, InstanceBus bus) {
        this.apiService = apiService;
        this.clock = clock;
        this.bus = bus;
        bus.on(CLEAR_TOPIC, ignored -> clearLocally());
    }

    public List<SocialLinkDto> links() {
        Entry current = entry.get();
        if (clock.instant().isBefore(current.until()) || !loading.tryLock()) return current.links();
        try {
            current = entry.get();
            Instant now = clock.instant();
            if (now.isBefore(current.until())) return current.links();
            long loadedAt = generation.get();
            List<SocialLinkDto> links = apiService.socialLinks();
            Entry loaded = links == null
                    ? new Entry(current.links(), now.plus(RETRY))
                    : new Entry(List.copyOf(links), now.plus(TTL));
            entry.set(loaded);
            // a clear() during the load: this list may predate the change, the next page loads it again
            if (generation.get() != loadedAt) entry.compareAndSet(loaded, loaded.stale());
            return loaded.links();
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
