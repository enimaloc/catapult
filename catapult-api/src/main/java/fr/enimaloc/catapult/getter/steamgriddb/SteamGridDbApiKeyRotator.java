package fr.enimaloc.catapult.getter.steamgriddb;

import fr.enimaloc.catapult.common.dto.admin.KeyUsageDto;
import fr.enimaloc.catapult.domain.steamgriddb.SteamGridDbApiKeyEntry;
import fr.enimaloc.catapult.getter.KeyCallCounter;
import fr.enimaloc.catapult.repository.steamgriddb.SteamGridDbApiKeyRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

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
 * Round robin over the shared SteamGridDB API keys, skipping those blocked by a rate limit. SteamGridDB publishes no
 * quota, so unlike the DTDD one it keeps no quotas: only the keys' blocks and the calls sent with each.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("catapult.enrichment.steamgriddb.enabled")
public class SteamGridDbApiKeyRotator {

    private final SteamGridDbApiKeyRepository repository;
    private final MeterRegistry meterRegistry;
    private final Map<String, Long> keyBlockedUntil = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile List<String> keys = List.of();
    private final KeyCallCounter calls =
            new KeyCallCounter(Duration.ofMinutes(1), at -> at.atZone(ZoneOffset.UTC).toLocalDate());

    public SteamGridDbApiKeyRotator(SteamGridDbApiKeyRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        // Pre-registers the counter so it is exported at 0 before the first event
        meterRegistry.counter("catapult.external.rate_limited", "api", "steamgriddb", "scope", "key");
        Gauge.builder("catapult.external.keys", this, SteamGridDbApiKeyRotator::availableKeyCount)
            .tag("api", "steamgriddb").tag("state", "available")
            .description("Usable SteamGridDB keys (shared pool, not rate-limited)")
            .register(meterRegistry);
        Gauge.builder("catapult.external.keys", this, SteamGridDbApiKeyRotator::blockedKeyCount)
            .tag("api", "steamgriddb").tag("state", "blocked")
            .description("SteamGridDB keys blocked by rate limit")
            .register(meterRegistry);
        refreshKeys();
    }

    public int blockedKeyCount() {
        long now = System.currentTimeMillis();
        return (int) keys.stream().filter(k -> keyBlockedUntil.getOrDefault(k, 0L) > now).count();
    }

    public int availableKeyCount() {
        return keys.size() - blockedKeyCount();
    }

    public void refreshKeys() {
        this.keys = repository.findByExclusiveFalse()
            .stream()
            .map(SteamGridDbApiKeyEntry::getApiKey)
            .toList();
        log.info("SteamGridDB API key pool refreshed: {} key(s)", keys.size());
    }

    /** The next usable key, round robin; when all are blocked, the one that frees first; empty for an empty pool. */
    public Optional<String> nextKey() {
        List<String> snapshot = keys;
        if (snapshot.isEmpty()) return Optional.empty();

        long now = System.currentTimeMillis();
        List<String> available = snapshot.stream()
            .filter(k -> keyBlockedUntil.getOrDefault(k, 0L) <= now)
            .toList();

        if (!available.isEmpty()) {
            return Optional.of(available.get(Math.floorMod(counter.getAndIncrement(), available.size())));
        }

        return snapshot.stream()
            .min(Comparator.comparingLong(k -> keyBlockedUntil.getOrDefault(k, 0L)));
    }

    public Map<String, Long> getKeyBlockedUntil() {
        return Map.copyOf(keyBlockedUntil);
    }

    /** Blocks {@code key} for {@code duration} (SteamGridDB answered it a 429). */
    public void block(String key, Duration duration) {
        keyBlockedUntil.put(key, System.currentTimeMillis() + duration.toMillis());
        meterRegistry.counter("catapult.external.rate_limited", "api", "steamgriddb", "scope", "key").increment();
        String masked = key.length() > 8
            ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
            : "…";
        log.warn("SteamGridDB key {} rate limited for {}s", masked, duration.toSeconds());
    }

    /** A call about to be sent with {@code key}: counted for the admin Keys tab. */
    public void onCall(String key) {
        calls.record(key, Instant.now());
    }

    /** What {@code key} was used for on this instance: its calls of the last minute and of the day (UTC), no limits. */
    public KeyUsageDto usage(String key) {
        Instant now = Instant.now();
        return new KeyUsageDto(calls.inWindow(key, now), null, 60, null, "DAY", calls.inPeriod(key, now), null, null);
    }
}
