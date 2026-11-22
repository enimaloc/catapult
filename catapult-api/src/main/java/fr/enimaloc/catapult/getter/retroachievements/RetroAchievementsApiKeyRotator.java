package fr.enimaloc.catapult.getter.retroachievements;

import fr.enimaloc.catapult.common.dto.admin.KeyUsageDto;
import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsApiKeyEntry;
import fr.enimaloc.catapult.getter.KeyCallCounter;
import fr.enimaloc.catapult.repository.retroachievements.RetroAchievementsApiKeyRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
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
 * Round robin over the shared RetroAchievements API keys, skipping those set aside: blocked by a rate limit, or refused
 * (401) and left alone for an hour. RetroAchievements publishes no per-key quota, so only the blocks and the calls sent
 * with each key are kept.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("catapult.enrichment.ra-user.enabled")
public class RetroAchievementsApiKeyRotator {

    private final RetroAchievementsApiKeyRepository repository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<String, Long> keyBlockedUntil = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile List<String> keys = List.of();
    private final KeyCallCounter calls =
            new KeyCallCounter(Duration.ofMinutes(1), at -> at.atZone(ZoneOffset.UTC).toLocalDate());

    @Autowired
    public RetroAchievementsApiKeyRotator(RetroAchievementsApiKeyRepository repository, MeterRegistry meterRegistry) {
        this(repository, meterRegistry, Clock.systemUTC());
    }

    RetroAchievementsApiKeyRotator(RetroAchievementsApiKeyRepository repository, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        // Pre-registers the counter so it is exported at 0 before the first event
        meterRegistry.counter("catapult.external.rate_limited", "api", "retroachievements", "scope", "key");
        Gauge.builder("catapult.external.keys", this, RetroAchievementsApiKeyRotator::availableKeyCount)
            .tag("api", "retroachievements").tag("state", "available")
            .description("Usable RetroAchievements keys (shared pool, not rate-limited)")
            .register(meterRegistry);
        Gauge.builder("catapult.external.keys", this, RetroAchievementsApiKeyRotator::blockedKeyCount)
            .tag("api", "retroachievements").tag("state", "blocked")
            .description("RetroAchievements keys blocked by rate limit")
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

    /** Whether the pool holds any key, blocked or not. */
    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    public void refreshKeys() {
        this.keys = repository.findByExclusiveFalse()
            .stream()
            .map(RetroAchievementsApiKeyEntry::getApiKey)
            .toList();
        log.info("RetroAchievements API key pool refreshed: {} key(s)", keys.size());
    }

    /** The next usable key, round robin; when all are blocked, the one that frees first; empty for an empty pool. */
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

        return snapshot.stream()
            .min(Comparator.comparingLong(k -> keyBlockedUntil.getOrDefault(k, 0L)));
    }

    public Map<String, Long> getKeyBlockedUntil() {
        return Map.copyOf(keyBlockedUntil);
    }

    /** The key RetroAchievements refused (401): set aside for {@code duration}, a rejected key is not retried at once. */
    public void onKeyRejected(String key, Duration duration) {
        keyBlockedUntil.put(key, clock.millis() + duration.toMillis());
        log.warn("RetroAchievements key {} rejected, set aside for {}s", mask(key), duration.toSeconds());
    }

    /** The key RetroAchievements answered a 429: blocked for {@code retryAfterSeconds}. */
    public void onKeyRateLimited(String key, int retryAfterSeconds) {
        keyBlockedUntil.put(key, clock.millis() + retryAfterSeconds * 1000L);
        meterRegistry.counter("catapult.external.rate_limited", "api", "retroachievements", "scope", "key").increment();
        log.warn("RetroAchievements key {} rate limited for {}s", mask(key), retryAfterSeconds);
    }

    private static String mask(String key) {
        return key.length() > 8 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4) : "…";
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
