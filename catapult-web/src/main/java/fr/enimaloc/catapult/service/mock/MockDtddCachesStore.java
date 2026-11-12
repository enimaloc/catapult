package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DtddCacheEntryDetailDto;
import fr.enimaloc.catapult.common.dto.admin.DtddCachesSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.DtddGameCacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.DtddKeyQuotaDto;
import fr.enimaloc.catapult.common.dto.admin.DtddRefetchJobDto;
import fr.enimaloc.catapult.common.dto.admin.DtddSearchCacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.DtddTopicSeverityDto;
import fr.enimaloc.catapult.common.dto.admin.DtddTopicsCacheEntryDto;
import fr.enimaloc.catapult.service.http.ApiClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The mock's DTDD caches: a few titles' topics, titles and searches, some past their lifetime, two keys' quotas, and
 * a bulk refetch that moves on by one entry each time it is read, so the panel's progress shows. Answers like
 * catapult-api: 404 for an entry not cached, 409 for a second refetch.
 */
class MockDtddCachesStore {
    static final int DEFAULT_PAGE_SIZE = 3;
    static final int TOPICS_TTL_HOURS = 168;
    static final int GAMES_TTL_HOURS = 180 * 24;
    static final int SEARCHES_TTL_HOURS = 30 * 24;
    static final int MONTHLY_RESERVE = 500;

    private final Clock clock;
    private final int pageSize;
    private final List<DtddTopicsCacheEntryDto> topics = new ArrayList<>();
    private final List<DtddGameCacheEntryDto> games = new ArrayList<>();
    private final List<DtddSearchCacheEntryDto> searches = new ArrayList<>();
    private DtddRefetchJobDto job = DtddRefetchJobDto.IDLE;

    MockDtddCachesStore() {
        this(Clock.systemUTC(), DEFAULT_PAGE_SIZE);
    }

    MockDtddCachesStore(Clock clock, int pageSize) {
        this.clock = clock;
        this.pageSize = pageSize;
        Instant now = clock.instant();
        topics.add(new DtddTopicsCacheEntryDto(16758, "Halo Infinite", List.of("a dog dies"), List.of("a cat dies"),
                List.of("jump scares"), now.minus(Duration.ofDays(9)), false, Map.of(
                        "a dog dies", new DtddTopicSeverityDto(List.of("Implied", "Depicted", "Excessive"),
                                List.of(2, 9, 1), List.of(17, 75, 8), 12, 2),
                        "jump scares", new DtddTopicSeverityDto(List.of("Mild", "Shown", "Severe"),
                                List.of(5, 3, 0), List.of(63, 37, 0), 8, null))));
        topics.add(new DtddTopicsCacheEntryDto(20311, "Elden Ring", List.of("gore", "body horror"), List.of(),
                List.of("spiders"), now.minus(Duration.ofDays(2)), false));
        topics.add(new DtddTopicsCacheEntryDto(18842, null, List.of(), List.of("a dog dies"), List.of(),
                now.minus(Duration.ofHours(5)), false));
        topics.add(new DtddTopicsCacheEntryDto(21007, "Stardew Valley", List.of(), List.of("gore"), List.of(),
                now.minus(Duration.ofDays(30)), false));
        games.add(new DtddGameCacheEntryDto(16758, "Halo Infinite", "Video Game", null, now.minus(Duration.ofDays(200)), false));
        games.add(new DtddGameCacheEntryDto(20311, "Elden Ring", "Video Game", null, now.minus(Duration.ofDays(40)), false));
        games.add(new DtddGameCacheEntryDto(21007, "Stardew Valley", "Video Game", null, now.minus(Duration.ofDays(3)), false));
        searches.add(new DtddSearchCacheEntryDto("halo", List.of(16758L), now.minus(Duration.ofDays(45)), false));
        searches.add(new DtddSearchCacheEntryDto("elden ring", List.of(20311L), now.minus(Duration.ofDays(4)), false));
    }

    synchronized DtddCachesSummaryDto summary() {
        advance();
        return new DtddCachesSummaryDto(topics.size(), stale(topics, DtddTopicsCacheEntryDto::fetchedAt, TOPICS_TTL_HOURS),
                games.size(), stale(games, DtddGameCacheEntryDto::fetchedAt, GAMES_TTL_HOURS),
                searches.size(), stale(searches, DtddSearchCacheEntryDto::searchedAt, SEARCHES_TTL_HOURS),
                TOPICS_TTL_HOURS, GAMES_TTL_HOURS, SEARCHES_TTL_HOURS,
                List.of(new DtddKeyQuotaDto("a1b2c3d4e5f60718", "ddd_…mnop", 27, 4120,
                                clock.instant().minusSeconds(40), null),
                        new DtddKeyQuotaDto("0f1e2d3c4b5a6978", "ddd_…2345", 0, 612, clock.instant().minusSeconds(10),
                                clock.instant().plusSeconds(50))),
                MONTHLY_RESERVE, job);
    }

    synchronized AdminPage<DtddTopicsCacheEntryDto> topics(String q, int page) {
        return page(topics, t -> matches(q, t.dtddId(), t.name()), DtddTopicsCacheEntryDto::fetchedAt, page,
                t -> new DtddTopicsCacheEntryDto(t.dtddId(), t.name(), t.yesTopics(), t.noTopics(), t.mostlyTopics(),
                        t.fetchedAt(), isStale(t.fetchedAt(), TOPICS_TTL_HOURS), t.severities()));
    }

    synchronized AdminPage<DtddGameCacheEntryDto> games(String q, int page) {
        return page(games, g -> matches(q, g.dtddId(), g.name()), DtddGameCacheEntryDto::fetchedAt, page,
                g -> new DtddGameCacheEntryDto(g.dtddId(), g.name(), g.mediaType(), g.posterUrl(), g.fetchedAt(),
                        isStale(g.fetchedAt(), GAMES_TTL_HOURS)));
    }

    synchronized AdminPage<DtddSearchCacheEntryDto> searches(String q, int page) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return page(searches, s -> s.query().contains(needle), DtddSearchCacheEntryDto::searchedAt, page,
                s -> new DtddSearchCacheEntryDto(s.query(), s.dtddIds(), s.searchedAt(),
                        isStale(s.searchedAt(), SEARCHES_TTL_HOURS)));
    }

    /** Everything cached about an entry, as catapult-api gives it; null when it isn't cached. */
    synchronized DtddCacheEntryDetailDto detail(String kind, String key) {
        String trimmed = key == null ? "" : key.trim();
        if (kind.equals("searches")) {
            String query = trimmed.toLowerCase(Locale.ROOT);
            return searches(query, 0).content().stream().filter(s -> s.query().equals(query)).findFirst()
                    .map(s -> new DtddCacheEntryDetailDto(null, query, null, null, List.of(s), s.dtddIds().stream()
                            .map(id -> new DtddCacheEntryDetailDto.FoundTitle(id,
                                    games.stream().filter(g -> g.dtddId() == id).map(DtddGameCacheEntryDto::name)
                                            .findFirst().orElse(null),
                                    topics.stream().anyMatch(t -> t.dtddId() == id)))
                            .toList()))
                    .orElse(null);
        }
        long id;
        try {
            id = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
        DtddTopicsCacheEntryDto topic = topics(String.valueOf(id), 0).content().stream()
                .filter(t -> t.dtddId() == id).findFirst().orElse(null);
        DtddGameCacheEntryDto game = games(String.valueOf(id), 0).content().stream()
                .filter(g -> g.dtddId() == id).findFirst().orElse(null);
        if (kind.equals("topics") ? topic == null : game == null) return null;
        return new DtddCacheEntryDetailDto(id, null, game, topic,
                searches.stream().filter(s -> s.dtddIds().contains(id))
                        .map(s -> new DtddSearchCacheEntryDto(s.query(), s.dtddIds(), s.searchedAt(),
                                isStale(s.searchedAt(), SEARCHES_TTL_HOURS)))
                        .toList(),
                List.of());
    }

    /** The entry as fetched just now: 200, 404 when it isn't cached, 400 for an unknown cache or a title key not an ID. */
    synchronized int refetch(String kind, String key) {
        Instant now = clock.instant();
        String trimmed = key == null ? "" : key.trim();
        switch (kind) {
            case "topics", "games" -> {
                long id;
                try {
                    id = Long.parseLong(trimmed);
                } catch (NumberFormatException e) {
                    return 400;
                }
                if (kind.equals("topics")) {
                    int i = indexOf(topics, t -> t.dtddId() == id);
                    if (i < 0) return 404;
                    DtddTopicsCacheEntryDto t = topics.get(i);
                    topics.set(i, new DtddTopicsCacheEntryDto(id, t.name(), t.yesTopics(), t.noTopics(),
                            t.mostlyTopics(), now, false, t.severities()));
                } else {
                    int i = indexOf(games, g -> g.dtddId() == id);
                    if (i < 0) return 404;
                    DtddGameCacheEntryDto g = games.get(i);
                    games.set(i, new DtddGameCacheEntryDto(id, g.name(), g.mediaType(), g.posterUrl(), now, false));
                }
                return 200;
            }
            case "searches" -> {
                String query = trimmed.toLowerCase(Locale.ROOT);
                int i = indexOf(searches, s -> s.query().equals(query));
                if (i < 0) return 404;
                searches.set(i, new DtddSearchCacheEntryDto(query, searches.get(i).dtddIds(), now, false));
                return 200;
            }
            default -> {
                return 404;
            }
        }
    }

    synchronized ApiClient.Result<DtddRefetchJobDto> start() {
        if ("RUNNING".equals(job.state())) return new ApiClient.Result<>(409, null);
        int total = topics.size() + games.size() + searches.size();
        job = new DtddRefetchJobDto("RUNNING", total, 0, 0, null, clock.instant(), null, null);
        return new ApiClient.Result<>(202, job);
    }

    synchronized ApiClient.Result<DtddRefetchJobDto> cancel() {
        if ("RUNNING".equals(job.state())) {
            job = new DtddRefetchJobDto("CANCELLED", job.total(), job.done(), job.failed(), null, job.startedAt(),
                    clock.instant(), null);
        }
        return new ApiClient.Result<>(200, job);
    }

    /** A running refetch moves on by one entry each time it is read; the mock's last one gives nothing. */
    private void advance() {
        if (!"RUNNING".equals(job.state())) return;
        int done = job.done() + 1;
        int failed = done == job.total() ? 1 : job.failed();
        job = done >= job.total()
                ? new DtddRefetchJobDto("DONE", job.total(), done, failed, null, job.startedAt(), clock.instant(), null)
                : new DtddRefetchJobDto("RUNNING", job.total(), done, failed, null, job.startedAt(), null, null);
    }

    private boolean isStale(Instant at, int ttlHours) {
        return at.isBefore(clock.instant().minus(Duration.ofHours(ttlHours)));
    }

    private <T> long stale(List<T> all, Function<T, Instant> at, int ttlHours) {
        return all.stream().filter(e -> isStale(at.apply(e), ttlHours)).count();
    }

    private static boolean matches(String q, long id, String name) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return String.valueOf(id).startsWith(needle) || name != null && name.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static <T> int indexOf(List<T> all, Predicate<T> match) {
        for (int i = 0; i < all.size(); i++) {
            if (match.test(all.get(i))) return i;
        }
        return -1;
    }

    /** The {@code page} of {@code all} matching, the oldest first, each one as now ({@code fresh}: its staleness). */
    private <T> AdminPage<T> page(List<T> all, Predicate<T> match, Function<T, Instant> at, int page,
                                  Function<T, T> fresh) {
        List<T> found = all.stream().filter(match).sorted(Comparator.comparing(at)).map(fresh).toList();
        int from = (int) Math.min((long) Math.max(page, 0) * pageSize, found.size());
        int to = Math.min(from + pageSize, found.size());
        int totalPages = (found.size() + pageSize - 1) / pageSize;
        return new AdminPage<>(found.subList(from, to), Math.max(page, 0), totalPages, found.size());
    }
}
