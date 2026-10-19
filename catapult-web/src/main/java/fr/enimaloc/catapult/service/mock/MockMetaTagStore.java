package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute;
import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The mock's meta tags: one store for the whole application, not per mock session, since every page
 * shows them, logged in or not. Mirrors catapult-api's rules (MetaTagService): a plain key, not one
 * the site writes itself, unique per attribute whatever its case; a content of one line.
 */
class MockMetaTagStore {
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9:._-]{0,99}");
    private static final Set<String> RESERVED = Set.of("viewport", "description", "robots", "og:type", "og:site_name",
            "og:title", "og:description", "og:url", "twitter:card", "sentry-trace", "baggage");
    private final Map<Long, MetaTagDto> tags = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    MockMetaTagStore() {
        long id = ids.incrementAndGet();
        tags.put(id, new MetaTagDto(id, MetaTagAttribute.NAME, "theme-color", "#1b1b1f", Instant.now()));
    }

    synchronized List<MetaTagDto> list() {
        return tags.values().stream()
                .sorted(Comparator.comparing(MetaTagDto::attribute).thenComparing(MetaTagDto::key))
                .toList();
    }

    synchronized int create(MetaTagRequest r) {
        if (!valid(r)) return 400;
        if (taken(r, 0)) return 409;
        long id = ids.incrementAndGet();
        tags.put(id, new MetaTagDto(id, r.attribute(), r.key(), r.content(), Instant.now()));
        return 201;
    }

    synchronized int update(long id, MetaTagRequest r) {
        if (!tags.containsKey(id)) return 404;
        if (!valid(r)) return 400;
        if (taken(r, id)) return 409;
        tags.put(id, new MetaTagDto(id, r.attribute(), r.key(), r.content(), Instant.now()));
        return 200;
    }

    synchronized int delete(long id) {
        return tags.remove(id) == null ? 404 : 204;
    }

    private boolean taken(MetaTagRequest r, long self) {
        return tags.values().stream().anyMatch(t -> t.id() != self && t.attribute() == r.attribute()
                && t.key().equalsIgnoreCase(r.key()));
    }

    private static boolean valid(MetaTagRequest r) {
        return r != null && r.attribute() != null && r.key() != null && KEY.matcher(r.key()).matches()
                && !RESERVED.contains(r.key().toLowerCase(Locale.ROOT))
                && r.content() != null && r.content().length() <= 1000
                && r.content().chars().noneMatch(Character::isISOControl);
    }
}
