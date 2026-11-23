package fr.enimaloc.catapult.getter.hypixel;

import fr.enimaloc.catapult.common.dto.admin.KeyUsageDto;
import fr.enimaloc.catapult.domain.hypixel.HypixelApiKeyEntry;
import fr.enimaloc.catapult.getter.KeyCallCounter;
import fr.enimaloc.catapult.repository.hypixel.HypixelApiKeyRepository;
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
 * Round robin over the shared Hypixel API keys, skipping those set aside: refused by Hypixel (403, left alone for an
 * hour) or blocked by a 429. An empty pool is the Hypixel source being off. The per-key rate limits themselves are
 * the {@code HypixelRateLimiter}'s.
 */
@Slf4j
@Component
public class HypixelApiKeyRotator {

    private final HypixelApiKeyRepository repository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<String, Long> keyBlockedUntil = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile List<String> keys = List.of();
    private final KeyCallCounter calls =
            new KeyCallCounter(Duration.ofMinutes(5), at -> at.atZone(ZoneOffset.UTC).toLocalDate());

    @Autowired
    public HypixelApiKeyRotator(HypixelApiKeyRepository repository, MeterRegistry meterRegistry) {
        this(repository, meterRegistry, Clock.systemUTC());
    }

    HypixelApiKeyRotator(HypixelApiKeyRepository repository, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        meterRegistry.counter("catapult.external.rate_limited", "api", "hypixel", "scope", "key");
        Gauge.builder("catapult.external.keys", this, HypixelApiKeyRotator::availableKeyCount)
            .tag("api", "hypixel").tag("state", "available")
            .description("Usable Hypixel keys (shared pool, not set aside)")
            .register(meterRegistry);
        Gauge.builder("catapult.external.keys", this, HypixelApiKeyRotator::blockedKeyCount)
            .tag("api", "hypixel").tag("state", "blocked")
            .description("Hypixel keys set aside (refused or rate limited)")
            .register(meterRegistry);
        refreshKeys();
    }

    public int blockedKeyCount() {
        long now = clock.millis();
        return (int) keys.stream().filter(k -> keyBlockedUntil.getOrDefault(k, 0L) > now).count();
    }

    public int availableKeyCount() {
        return keys.size() - blockedKeyCount();
    }

    /** Whether the pool holds any key, blocked or not: without one the Hypixel source is off. */
    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    public void refreshKeys() {
        this.keys = repository.findByExclusiveFalse().stream().map(HypixelApiKeyEntry::getApiKey).toList();
        log.info("Hypixel API key pool refreshed: {} key(s)", keys.size());
    }

    /** The next usable key, round robin; when all are set aside, the one that frees first; empty for an empty pool. */
    public Optional<String> nextKey() {
        List<String> all = keys;
        if (all.isEmpty()) return Optional.empty();

        long now = clock.millis();
        List<String> available = all.stream()
            .filter(k -> keyBlockedUntil.getOrDefault(k, 0L) <= now)
            .toList();
        if (!available.isEmpty()) {
            return Optional.of(available.get(Math.floorMod(counter.getAndIncrement(), available.size())));
        }
        return all.stream().min(Comparator.comparingLong(k -> keyBlockedUntil.getOrDefault(k, 0L)));
    }

    /** Whether every key of the pool is set aside right now. */
    public boolean allBlocked() {
        List<String> all = keys;
        return !all.isEmpty() && blockedKeyCount() >= all.size();
    }

    public Map<String, Long> getKeyBlockedUntil() {
        return Map.copyOf(keyBlockedUntil);
    }

    /** The key Hypixel refused (403): set aside for {@code duration}, a refused key is not retried at once. */
    public void onKeyRejected(String key, Duration duration) {
        keyBlockedUntil.put(key, clock.millis() + duration.toMillis());
        log.warn("Hypixel key {} refused, set aside for {}s", mask(key), duration.toSeconds());
    }

    /** The key Hypixel answered a 429 for: blocked for {@code duration}. */
    public void onKeyRateLimited(String key, Duration duration) {
        keyBlockedUntil.put(key, clock.millis() + duration.toMillis());
        meterRegistry.counter("catapult.external.rate_limited", "api", "hypixel", "scope", "key").increment();
        log.warn("Hypixel key {} rate limited for {}s", mask(key), duration.toSeconds());
    }

    static String mask(String key) {
        return key.length() > 8 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4) : "…";
    }

    /** A call about to be sent with {@code key}: counted for the admin Keys tab. */
    public void onCall(String key) {
        calls.record(key, Instant.now());
    }

    /** What {@code key} was used for on this instance: its calls of the last five minutes and of the day (UTC). */
    public KeyUsageDto usage(String key) {
        Instant now = Instant.now();
        return new KeyUsageDto(calls.inWindow(key, now), null, 300, null, "DAY", calls.inPeriod(key, now), null,
                null);
    }
}
