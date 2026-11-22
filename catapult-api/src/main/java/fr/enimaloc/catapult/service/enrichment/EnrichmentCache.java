package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Read-through cache shared by the enrichment sources: a fresh entry is served as is, a missing or stale one is
 * fetched (within the caller's {@link FetchBudget}), and a stale one is still served when the fetch fails.
 */
@Slf4j
@Service
public class EnrichmentCache {

    private final GameEnrichmentCacheRepository repository;
    private final EnrichmentProperties properties;
    private final JsonMapper mapper;
    private final Clock clock;

    @Autowired
    public EnrichmentCache(GameEnrichmentCacheRepository repository, EnrichmentProperties properties,
                           JsonMapper mapper) {
        this(repository, properties, mapper, Clock.systemUTC());
    }

    EnrichmentCache(GameEnrichmentCacheRepository repository, EnrichmentProperties properties, JsonMapper mapper,
                    Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.mapper     = mapper;
        this.clock      = clock;
    }

    /**
     * The cached or freshly fetched value for {@code lookupKey}; empty when the source is disabled or has nothing.
     *
     * @throws UpstreamBudgetExceededException when a fetch is needed but {@code budget} refuses it
     */
    public <T> Optional<T> get(EnrichmentSource source, String lookupKey, Class<T> type, FetchBudget budget,
                               Supplier<Optional<T>> fetch) {
        EnrichmentProperties.Source settings = properties.source(source);
        if (!settings.enabled()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<GameEnrichmentCacheEntry> cached = repository.findBySourceAndLookupKey(source.key(), lookupKey);
        if (cached.isPresent()) {
            GameEnrichmentCacheEntry entry = cached.get();
            Duration ttl = entry.getPayload() == null ? absentTtl(source) : settings.ttl();
            if (entry.getFetchedAt().plus(ttl).isAfter(now)) {
                Optional<Optional<T>> hit = read(entry, type);
                if (hit.isPresent()) {
                    return hit.get();
                }
            }
        }
        if (!budget.tryAcquire()) {
            // The budget only gates the upstream call: a stale value is still the last known one.
            if (cached.isPresent()) {
                return read(cached.get(), type).orElse(Optional.empty());
            }
            throw new UpstreamBudgetExceededException(budget.retryAfter());
        }
        Optional<T> fetched;
        try {
            fetched = fetch.get();
        } catch (RuntimeException e) {
            log.warn("Enrichment fetch failed for {} {}: {}", source.key(), lookupKey, e.toString());
            return cached.flatMap(entry -> read(entry, type)).flatMap(v -> v);
        }
        store(source, lookupKey, fetched.map(mapper::writeValueAsString).orElse(null), now);
        return fetched;
    }

    /** How long a "known absent" answer of {@code source} is trusted. */
    Duration absentTtl(EnrichmentSource source) {
        return source.personal() ? properties.personalAbsentTtl() : properties.absentTtl();
    }

    /** Forgets everything the personal sources cached for one member ({@code u:<id>} and {@code u:<id>:...}). */
    public void evictUser(UUID userId) {
        evictUser(userId, Arrays.stream(EnrichmentSource.values()).filter(EnrichmentSource::personal).toList());
    }

    /** Forgets what only the given personal sources cached for one member. */
    public void evictUser(UUID userId, java.util.Collection<EnrichmentSource> only) {
        List<String> sources = only.stream().filter(EnrichmentSource::personal).map(EnrichmentSource::key).toList();
        String key = "u:" + userId;
        repository.deleteByKeyOfSources(sources, key, key + ":%");
    }

    /**
     * Stores {@code value} under {@code lookupKey} as just fetched, so that a lookup by another key of the same
     * answer (a detail route's id) is served without a second upstream call. Never records an absence; does nothing
     * when the source is disabled.
     */
    public <T> void put(EnrichmentSource source, String lookupKey, T value) {
        if (value == null || !properties.source(source).enabled()) {
            return;
        }
        store(source, lookupKey, mapper.writeValueAsString(value), clock.instant());
    }

    /**
     * Saves one entry. Two first fetches of the same key may race to insert it: the loser's duplicate key is not an
     * error, the winner's entry holds the same answer.
     */
    private void store(EnrichmentSource source, String lookupKey, String payload, Instant fetchedAt) {
        try {
            repository.save(new GameEnrichmentCacheEntry(source.key(), lookupKey, payload, fetchedAt));
        } catch (DataIntegrityViolationException e) {
            log.debug("Enrichment entry {} {} stored concurrently: {}", source.key(), lookupKey, e.toString());
        }
    }

    /** The entry's value (empty inside when known absent), or empty when its payload is unreadable. */
    private <T> Optional<Optional<T>> read(GameEnrichmentCacheEntry entry, Class<T> type) {
        if (entry.getPayload() == null) {
            return Optional.of(Optional.empty());
        }
        try {
            return Optional.of(Optional.of(mapper.readValue(entry.getPayload(), type)));
        } catch (RuntimeException e) {
            log.warn("Unreadable cached enrichment payload for {} {}: {}", entry.getSource(), entry.getLookupKey(),
                    e.toString());
            return Optional.empty();
        }
    }
}
