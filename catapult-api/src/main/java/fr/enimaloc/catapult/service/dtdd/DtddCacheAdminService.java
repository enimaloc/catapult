package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.common.dto.admin.DtddCacheEntryDetailDto;
import fr.enimaloc.catapult.common.dto.admin.DtddCachesSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.DtddGameCacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.DtddKeyQuotaDto;
import fr.enimaloc.catapult.common.dto.admin.DtddSearchCacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.DtddTopicSeverityDto;
import fr.enimaloc.catapult.common.dto.admin.DtddTopicsCacheEntryDto;
import fr.enimaloc.catapult.domain.dtdd.DtddApiKeyEntry;
import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddSearchCache;
import fr.enimaloc.catapult.domain.dtdd.DtddTopicSeverity;
import fr.enimaloc.catapult.domain.dtdd.DtddTopicsCache;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.getter.dtdd.DtddApiKeyRotator;
import fr.enimaloc.catapult.repository.dtdd.DtddApiKeyRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddSearchCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The DTDD caches as the admin sees them: each one paged and searched, the oldest first, with what is past its own
 * lifetime ({@code dtdd.cache.topics-ttl-hours}, {@code game-info-ttl-days}, {@code search-ttl-days}); an entry
 * fetched again on demand; the keys' quotas and the bulk refetch ({@link DtddCacheRefetchJob}).
 */
@Service
public class DtddCacheAdminService {

    static final int MAX_PAGE_SIZE = 100;

    private final DtddTopicsCacheRepository topicsRepo;
    private final DtddGameCacheRepository gameRepo;
    private final DtddSearchCacheRepository searchRepo;
    private final DtddApiKeyRepository keyRepo;
    private final Optional<DtddApiClient> client;
    private final Optional<DtddApiKeyRotator> rotator;
    private final DtddCacheRefetcher refetcher;
    private final DtddCacheRefetchJob job;
    private final Map<DtddCacheKind, Duration> lifetimes;
    private final Clock clock;

    @Autowired
    public DtddCacheAdminService(DtddTopicsCacheRepository topicsRepo, DtddGameCacheRepository gameRepo,
                                 DtddSearchCacheRepository searchRepo, DtddApiKeyRepository keyRepo,
                                 Optional<DtddApiClient> client, Optional<DtddApiKeyRotator> rotator,
                                 DtddCacheRefetcher refetcher, DtddCacheRefetchJob job,
                                 @Value("${dtdd.cache.topics-ttl-hours:168}") int topicsTtlHours,
                                 @Value("${dtdd.cache.game-info-ttl-days:180}") int gamesTtlDays,
                                 @Value("${dtdd.cache.search-ttl-days:30}") int searchesTtlDays) {
        this(topicsRepo, gameRepo, searchRepo, keyRepo, client, rotator, refetcher, job,
                Map.of(DtddCacheKind.TOPICS, Duration.ofHours(topicsTtlHours),
                        DtddCacheKind.GAMES, Duration.ofDays(gamesTtlDays),
                        DtddCacheKind.SEARCHES, Duration.ofDays(searchesTtlDays)),
                Clock.systemUTC());
    }

    DtddCacheAdminService(DtddTopicsCacheRepository topicsRepo, DtddGameCacheRepository gameRepo,
                          DtddSearchCacheRepository searchRepo, DtddApiKeyRepository keyRepo,
                          Optional<DtddApiClient> client, Optional<DtddApiKeyRotator> rotator,
                          DtddCacheRefetcher refetcher, DtddCacheRefetchJob job,
                          Map<DtddCacheKind, Duration> lifetimes, Clock clock) {
        this.topicsRepo = topicsRepo;
        this.gameRepo = gameRepo;
        this.searchRepo = searchRepo;
        this.keyRepo = keyRepo;
        this.client = client;
        this.rotator = rotator;
        this.refetcher = refetcher;
        this.job = job;
        this.lifetimes = Map.copyOf(lifetimes);
        this.clock = clock;
    }

    public DtddCachesSummaryDto summary() {
        return new DtddCachesSummaryDto(
                topicsRepo.count(), topicsRepo.countByFetchedAtBefore(staleBefore(DtddCacheKind.TOPICS)),
                gameRepo.count(), gameRepo.countByFetchedAtBefore(staleBefore(DtddCacheKind.GAMES)),
                searchRepo.count(), searchRepo.countBySearchedAtBefore(staleBefore(DtddCacheKind.SEARCHES)),
                hours(DtddCacheKind.TOPICS), hours(DtddCacheKind.GAMES), hours(DtddCacheKind.SEARCHES),
                quotas(), job.monthlyReserve(), job.status());
    }

    /** The {@code kind} cache's entries matching {@code q} (none: all), the oldest first. */
    public Page<?> list(DtddCacheKind kind, String q, int page, int size) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case TOPICS -> topics(needle, pageable(page, size, "fetchedAt"));
            case GAMES -> gameRepo.search(needle, pageable(page, size, "fetchedAt")).map(this::game);
            case SEARCHES -> searchRepo.search(needle, pageable(page, size, "searchedAt")).map(this::search);
        };
    }

    /**
     * Fetches the {@code kind} entry {@code key} again (a title's ID, a search's query) and gives it as now cached.
     *
     * @throws ResponseStatusException 503 while DTDD is off, 404 for an entry not cached, 502 when DTDD gave
     *         nothing (an unknown title, no usable key, DTDD unavailable)
     */
    public Object refetch(DtddCacheKind kind, String key) {
        DtddApiClient dtdd = client.orElseThrow(
                () -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DTDD is disabled"));
        String normalized = key == null ? "" : key.trim();
        if (kind == DtddCacheKind.SEARCHES) normalized = normalized.toLowerCase(Locale.ROOT);
        if (!cached(kind, normalized)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not in the DTDD cache");
        }
        if (!refetcher.refetch(dtdd, kind, normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DTDD gave nothing for it");
        }
        return switch (kind) {
            case TOPICS -> topic(topicsRepo.findById(Long.parseLong(normalized)).orElseThrow(), names(
                    List.of(Long.parseLong(normalized))));
            case GAMES -> game(gameRepo.findById(Long.parseLong(normalized)).orElseThrow());
            case SEARCHES -> search(searchRepo.findById(normalized).orElseThrow());
        };
    }

    /**
     * Everything cached about the {@code kind} entry {@code key}: a title (from its topics or its record) with both
     * and the searches that found it; a search with the titles it found.
     *
     * @throws ResponseStatusException 404 for an entry not cached, 400 for a title key that isn't an ID
     */
    public DtddCacheEntryDetailDto detail(DtddCacheKind kind, String key) {
        String normalized = key == null ? "" : key.trim();
        if (kind == DtddCacheKind.SEARCHES) normalized = normalized.toLowerCase(Locale.ROOT);
        if (!cached(kind, normalized)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not in the DTDD cache");
        }
        if (kind == DtddCacheKind.SEARCHES) {
            DtddSearchCache found = searchRepo.findById(normalized).orElseThrow();
            List<Long> ids = found.getDtddIds() == null ? List.of() : found.getDtddIds();
            Map<Long, String> names = names(ids);
            Set<Long> withTopics = new HashSet<>();
            topicsRepo.findAllById(ids).forEach(t -> withTopics.add(t.getDtddId()));
            return new DtddCacheEntryDetailDto(null, normalized, null, null, List.of(search(found)),
                    ids.stream().map(id -> new DtddCacheEntryDetailDto.FoundTitle(id, names.get(id),
                            withTopics.contains(id))).toList());
        }
        long id = Long.parseLong(normalized);
        Map<Long, String> names = names(List.of(id));
        return new DtddCacheEntryDetailDto(id, null,
                gameRepo.findById(id).map(this::game).orElse(null),
                topicsRepo.findById(id).map(t -> topic(t, names)).orElse(null),
                searchRepo.findAllFinding(id).stream().map(this::search).toList(),
                List.of());
    }

    private boolean cached(DtddCacheKind kind, String key) {
        if (kind == DtddCacheKind.SEARCHES) return searchRepo.existsById(key);
        long id;
        try {
            id = Long.parseLong(key);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a DTDD title ID");
        }
        return kind == DtddCacheKind.TOPICS ? topicsRepo.existsById(id) : gameRepo.existsById(id);
    }

    private Page<DtddTopicsCacheEntryDto> topics(String needle, Pageable pageable) {
        Page<DtddTopicsCache> found = topicsRepo.search(needle, pageable);
        Map<Long, String> names = names(found.getContent().stream().map(DtddTopicsCache::getDtddId).toList());
        return found.map(t -> topic(t, names));
    }

    /** The cached names of these titles. */
    private Map<Long, String> names(List<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        gameRepo.findAllById(ids).forEach(g -> names.put(g.getDtddId(), g.getName()));
        return names;
    }

    private DtddTopicsCacheEntryDto topic(DtddTopicsCache t, Map<Long, String> names) {
        Map<String, DtddTopicSeverityDto> severities = null;
        if (t.getSeverities() != null) {
            severities = new LinkedHashMap<>();
            for (Map.Entry<String, DtddTopicSeverity> e : t.getSeverities().entrySet()) {
                DtddTopicSeverity s = e.getValue();
                severities.put(e.getKey(), new DtddTopicSeverityDto(s.levels(), s.counts(), s.percents(), s.total(),
                        s.verified()));
            }
        }
        return new DtddTopicsCacheEntryDto(t.getDtddId(), names.get(t.getDtddId()), t.getYesTopics(),
                t.getNoTopics(), t.getMostlyTopics(), t.getFetchedAt(), stale(DtddCacheKind.TOPICS, t.getFetchedAt()),
                severities);
    }

    private DtddGameCacheEntryDto game(DtddGameCache g) {
        return new DtddGameCacheEntryDto(g.getDtddId(), g.getName(), g.getMediaType(), g.getPosterUrl(),
                g.getFetchedAt(), stale(DtddCacheKind.GAMES, g.getFetchedAt()));
    }

    private DtddSearchCacheEntryDto search(DtddSearchCache s) {
        return new DtddSearchCacheEntryDto(s.getQueryNormalized(), s.getDtddIds(), s.getSearchedAt(),
                stale(DtddCacheKind.SEARCHES, s.getSearchedAt()));
    }

    /** Each pooled key's last known quota and rest, masked as the keys tab shows them. */
    private List<DtddKeyQuotaDto> quotas() {
        if (rotator.isEmpty()) return List.of();
        Map<String, DtddApiKeyRotator.Quota> quotas = rotator.get().getQuotas();
        Map<String, Long> blockedUntil = rotator.get().getKeyBlockedUntil();
        long now = clock.millis();
        List<DtddKeyQuotaDto> out = new ArrayList<>();
        for (DtddApiKeyEntry entry : keyRepo.findByExclusiveFalse()) {
            String key = entry.getApiKey();
            DtddApiKeyRotator.Quota quota = quotas.get(key);
            long until = blockedUntil.getOrDefault(key, 0L);
            out.add(new DtddKeyQuotaDto(ApiKeyHasher.id(key), DtddKeyPoolService.mask(key),
                    quota == null ? null : quota.remainingMinute(), quota == null ? null : quota.remainingMonth(),
                    quota == null ? null : Instant.ofEpochMilli(quota.at()),
                    until > now ? Instant.ofEpochMilli(until) : null));
        }
        return out;
    }

    private Instant staleBefore(DtddCacheKind kind) {
        return clock.instant().minus(lifetimes.get(kind));
    }

    private boolean stale(DtddCacheKind kind, Instant at) {
        return at == null || at.isBefore(staleBefore(kind));
    }

    private int hours(DtddCacheKind kind) {
        return (int) lifetimes.get(kind).toHours();
    }

    private static Pageable pageable(int page, int size, String oldestFirst) {
        int bounded = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page, 0), bounded, Sort.by(Sort.Direction.ASC, oldestFirst));
    }
}
