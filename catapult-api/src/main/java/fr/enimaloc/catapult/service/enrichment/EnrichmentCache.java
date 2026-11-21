package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
            Duration ttl = entry.getPayload() == null ? properties.absentTtl() : settings.ttl();
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
        String payload = fetched.map(mapper::writeValueAsString).orElse(null);
        repository.save(new GameEnrichmentCacheEntry(source.key(), lookupKey, payload, now));
        return fetched;
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
