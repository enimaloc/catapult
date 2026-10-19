package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.service.ApiService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The meta tags the admin configured (Meta tab), written into the head of every page by index.html.
 * catapult-api's list is kept 60 s, so a page doesn't reach it each time, and emptied when the admin
 * changes a tag. When catapult-api fails, the last list known is kept showing (none before the first).
 */
@ControllerAdvice(assignableTypes = {IndexController.class, ErrorPageController.class})
public class SiteMetaTags {
    static final Duration TTL = Duration.ofSeconds(60);

    private record Entry(List<MetaTagDto> tags, Instant until) {}

    private final ObjectProvider<ApiService> apiService;
    private final Clock clock;
    private volatile Entry entry;
    /** Bumped by {@link #clear()}: a list loaded before it may predate the change and isn't kept. */
    private final AtomicLong generation = new AtomicLong();

    @Autowired
    public SiteMetaTags(ObjectProvider<ApiService> apiService) {
        this(apiService, Clock.systemUTC());
    }

    SiteMetaTags(ObjectProvider<ApiService> apiService, Clock clock) {
        this.apiService = apiService;
        this.clock = clock;
    }

    @ModelAttribute("metaTags")
    public List<MetaTagDto> metaTags() {
        Instant now = clock.instant();
        Entry current = entry;
        if (current != null && now.isBefore(current.until())) return current.tags();
        long loadedAt = generation.get();
        ApiService api = apiService.getIfAvailable();
        List<MetaTagDto> tags = api == null ? null : api.metaTags();
        if (tags == null) return current == null ? List.of() : current.tags();
        Entry loaded = new Entry(List.copyOf(tags), now.plus(TTL));
        entry = loaded;
        // a clear() since the load: this list may predate the change, the next page loads it again
        if (generation.get() != loadedAt && entry == loaded) entry = null;
        return loaded.tags();
    }

    public void clear() {
        generation.incrementAndGet();
        entry = null;
    }
}
