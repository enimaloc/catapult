package fr.enimaloc.catapult.getter.riot;

import fr.enimaloc.catapult.common.dto.admin.KeyUsageDto;
import fr.enimaloc.catapult.domain.riot.RiotApiKeyEntry;
import fr.enimaloc.catapult.getter.KeyCallCounter;
import fr.enimaloc.catapult.repository.riot.RiotApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform.Cluster;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round robin over the shared Riot API keys that serve a routing cluster (those scoped to it, and the unscoped
 * ones), skipping those set aside: refused by Riot (401 / 403, left alone for an hour) or blocked by a 429. An empty
 * pool is the Riot source being off. The per-key, per-cluster rate limits themselves are the
 * {@code RiotRateLimiter}'s.
 */
@Slf4j
@Component
public class RiotApiKeyRotator {

    /** A key of the pool and the cluster it is kept to (null: every one). */
    private record PoolKey(String key, Cluster scope) {
    }

    private final RiotApiKeyRepository repository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<String, Long> keyBlockedUntil = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile List<PoolKey> keys = List.of();
    private final KeyCallCounter calls =
            new KeyCallCounter(Duration.ofMinutes(2), at -> at.atZone(ZoneOffset.UTC).toLocalDate());

    @Autowired
    public RiotApiKeyRotator(RiotApiKeyRepository repository, MeterRegistry meterRegistry) {
        this(repository, meterRegistry, Clock.systemUTC());
    }

    RiotApiKeyRotator(RiotApiKeyRepository repository, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        meterRegistry.counter("catapult.external.rate_limited", "api", "riot", "scope", "key");
        Gauge.builder("catapult.external.keys", this, RiotApiKeyRotator::availableKeyCount)
            .tag("api", "riot").tag("state", "available")
            .description("Usable Riot keys (shared pool, not set aside)")
            .register(meterRegistry);
        Gauge.builder("catapult.external.keys", this, RiotApiKeyRotator::blockedKeyCount)
            .tag("api", "riot").tag("state", "blocked")
            .description("Riot keys set aside (refused or rate limited)")
            .register(meterRegistry);
        refreshKeys();
    }

    public int blockedKeyCount() {
        long now = clock.millis();
        return (int) keys.stream().filter(k -> keyBlockedUntil.getOrDefault(k.key(), 0L) > now).count();
    }

    public int availableKeyCount() {
        return keys.size() - blockedKeyCount();
    }

    /** Whether the pool holds any key, blocked or not: without one the Riot source is off. */
    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    public void refreshKeys() {
        this.keys = repository.findByExclusiveFalse().stream()
            .map(e -> new PoolKey(e.getApiKey(), Cluster.parse(e.getRegionScope()).orElse(null)))
            .toList();
        log.info("Riot API key pool refreshed: {} key(s)", keys.size());
    }

    /**
     * The next usable key for {@code cluster}, round robin; when all are set aside, the one that frees first; empty
     * when no key serves the cluster.
     */
    public Optional<String> nextKey(Cluster cluster) {
        List<String> serving = keys.stream()
            .filter(k -> k.scope() == null || k.scope() == cluster)
            .map(PoolKey::key)
            .toList();
        if (serving.isEmpty()) return Optional.empty();

        long now = clock.millis();
        List<String> available = serving.stream()
            .filter(k -> keyBlockedUntil.getOrDefault(k, 0L) <= now)
            .toList();
        if (!available.isEmpty()) {
            return Optional.of(available.get(Math.floorMod(counter.getAndIncrement(), available.size())));
        }
        return serving.stream().min(Comparator.comparingLong(k -> keyBlockedUntil.getOrDefault(k, 0L)));
    }

    /** Whether some key serves {@code cluster}. */
    public boolean serves(Cluster cluster) {
        return keys.stream().anyMatch(k -> k.scope() == null || k.scope() == cluster);
    }

    /** Whether {@code key} is set aside now (refused or rate limited). */
    public boolean blocked(String key) {
        return keyBlockedUntil.getOrDefault(key, 0L) > clock.millis();
    }

    public Map<String, Long> getKeyBlockedUntil() {
        return Map.copyOf(keyBlockedUntil);
    }

    /** The key Riot refused (401 / 403): set aside for {@code duration}, a refused key is not retried at once. */
    public void onKeyRejected(String key, Duration duration) {
        keyBlockedUntil.put(key, clock.millis() + duration.toMillis());
        log.warn("Riot key {} refused, set aside for {}s", mask(key), duration.toSeconds());
    }

    /** The key Riot answered a 429 for its application limit: blocked for {@code duration}. */
    public void onKeyRateLimited(String key, Duration duration) {
        keyBlockedUntil.put(key, clock.millis() + duration.toMillis());
        meterRegistry.counter("catapult.external.rate_limited", "api", "riot", "scope", "key").increment();
        log.warn("Riot key {} rate limited for {}s", mask(key), duration.toSeconds());
    }

    static String mask(String key) {
        return key.length() > 8 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4) : "…";
    }

    /** A call about to be sent with {@code key}: counted for the admin Keys tab. */
    public void onCall(String key) {
        calls.record(key, Instant.now());
    }

    /** What {@code key} was used for on this instance: its calls of the last two minutes and of the day (UTC). */
    public KeyUsageDto usage(String key) {
        Instant now = Instant.now();
        return new KeyUsageDto(calls.inWindow(key, now), null, 120, null, "DAY", calls.inPeriod(key, now), null,
                null);
    }
}
