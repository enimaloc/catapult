package fr.enimaloc.catapult.getter.faceit;

import fr.enimaloc.catapult.common.dto.admin.KeyUsageDto;
import fr.enimaloc.catapult.domain.faceit.FaceitApiKeyEntry;
import fr.enimaloc.catapult.getter.KeyCallCounter;
import fr.enimaloc.catapult.repository.faceit.FaceitApiKeyRepository;
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
 * Round robin over the shared FACEIT Data API keys, skipping those set aside: refused by FACEIT (401 / 403, left
 * alone for a while) or blocked by a 429. An empty pool is the FACEIT source being off. The rate limits themselves are
 * the {@code FaceitRateLimiter}'s.
 */
@Slf4j
@Component
public class FaceitApiKeyRotator {

    private final FaceitApiKeyRepository repository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<String, Long> keyBlockedUntil = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile List<String> keys = List.of();
    private final KeyCallCounter calls =
            new KeyCallCounter(Duration.ofMinutes(1), at -> at.atZone(ZoneOffset.UTC).toLocalDate());

    @Autowired
    public FaceitApiKeyRotator(FaceitApiKeyRepository repository, MeterRegistry meterRegistry) {
        this(repository, meterRegistry, Clock.systemUTC());
    }

    FaceitApiKeyRotator(FaceitApiKeyRepository repository, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        meterRegistry.counter("catapult.external.rate_limited", "api", "faceit", "scope", "key");
        Gauge.builder("catapult.external.keys", this, FaceitApiKeyRotator::availableKeyCount)
            .tag("api", "faceit").tag("state", "available")
            .description("Usable FACEIT keys (shared pool, not set aside)")
            .register(meterRegistry);
        Gauge.builder("catapult.external.keys", this, FaceitApiKeyRotator::blockedKeyCount)
            .tag("api", "faceit").tag("state", "blocked")
            .description("FACEIT keys set aside (refused or rate limited)")
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

    /** Whether the pool holds any key, blocked or not: without one the FACEIT source is off. */
    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    public void refreshKeys() {
        this.keys = repository.findByExclusiveFalse().stream().map(FaceitApiKeyEntry::getApiKey).toList();
        log.info("FACEIT API key pool refreshed: {} key(s)", keys.size());
    }

    /** The next usable key, round robin; when all are set aside, the one that frees first; empty for an empty pool. */
    public Optional<String> nextKey() {
        List<String> snapshot = keys;
        if (snapshot.isEmpty()) return Optional.empty();

        long now = clock.millis();
        List<String> available = snapshot.stream()
            .filter(k -> keyBlockedUntil.getOrDefault(k, 0L) <= now)
            .toList();
        if (!available.isEmpty()) {
            return Optional.of(available.get(Math.floorMod(counter.getAndIncrement(), available.size())));
        }
        return snapshot.stream().min(Comparator.comparingLong(k -> keyBlockedUntil.getOrDefault(k, 0L)));
    }

    public Map<String, Long> getKeyBlockedUntil() {
        return Map.copyOf(keyBlockedUntil);
    }

    /** The key FACEIT refused (401 / 403): set aside for {@code duration}, a refused key is not retried at once. */
    public void onKeyRejected(String key, Duration duration) {
        keyBlockedUntil.put(key, clock.millis() + duration.toMillis());
        log.warn("FACEIT key {} refused, set aside for {}s", mask(key), duration.toSeconds());
    }

    /** The key FACEIT answered a 429 for: blocked for {@code duration}. */
    public void onKeyRateLimited(String key, Duration duration) {
        keyBlockedUntil.put(key, clock.millis() + duration.toMillis());
        meterRegistry.counter("catapult.external.rate_limited", "api", "faceit", "scope", "key").increment();
        log.warn("FACEIT key {} rate limited for {}s", mask(key), duration.toSeconds());
    }

    static String mask(String key) {
        return key.length() > 8 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4) : "…";
    }

    /** A call about to be sent with {@code key}: counted for the admin Keys tab. */
    public void onCall(String key) {
        calls.record(key, Instant.now());
    }

    /** What {@code key} was used for on this instance: its calls of the last minute and of the day (UTC). */
    public KeyUsageDto usage(String key) {
        Instant now = Instant.now();
        return new KeyUsageDto(calls.inWindow(key, now), null, 60, null, "DAY", calls.inPeriod(key, now), null, null);
    }
}
