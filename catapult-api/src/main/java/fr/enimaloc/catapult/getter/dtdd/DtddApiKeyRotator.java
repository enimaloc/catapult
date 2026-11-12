package fr.enimaloc.catapult.getter.dtdd;

import fr.enimaloc.catapult.domain.dtdd.DtddApiKeyEntry;
import fr.enimaloc.catapult.repository.dtdd.DtddApiKeyRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@ConditionalOnBooleanProperty("dtdd.enabled")
public class DtddApiKeyRotator {

    private final DtddApiKeyRepository repository;
    private final MeterRegistry meterRegistry;
    private final Map<String, Long> keyBlockedUntil = new ConcurrentHashMap<>();
    private final Map<String, Quota> quotas = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile List<String> keys = List.of();

    public DtddApiKeyRotator(DtddApiKeyRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        // Pre-registers the counter so it is exported at 0 before the first event
        meterRegistry.counter("catapult.external.rate_limited", "api", "dtdd", "scope", "key");
        Gauge.builder("catapult.external.keys", this, DtddApiKeyRotator::availableKeyCount)
            .tag("api", "dtdd").tag("state", "available")
            .description("Usable DTDD keys (shared pool, not rate-limited)")
            .register(meterRegistry);
        Gauge.builder("catapult.external.keys", this, DtddApiKeyRotator::blockedKeyCount)
            .tag("api", "dtdd").tag("state", "blocked")
            .description("DTDD keys blocked by rate limit")
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
            .map(DtddApiKeyEntry::getApiKey)
            .toList();
        log.info("DTDD API key pool refreshed: {} key(s)", keys.size());
    }

    public Optional<String> nextKey() {
        List<String> snapshot = keys;
        if (snapshot.isEmpty()) return Optional.empty();

        long now = System.currentTimeMillis();
        List<String> available = snapshot.stream()
            .filter(k -> keyBlockedUntil.getOrDefault(k, 0L) <= now)
            .toList();

        if (!available.isEmpty()) {
            int idx = Math.floorMod(counter.getAndIncrement(), available.size());
            return Optional.of(available.get(idx));
        }

        return snapshot.stream()
            .min(Comparator.comparingLong(k -> keyBlockedUntil.getOrDefault(k, 0L)));
    }

    public Map<String, Long> getKeyBlockedUntil() {
        return Map.copyOf(keyBlockedUntil);
    }

    public boolean isAllKeysBlocked() {
        List<String> snapshot = keys;
        if (snapshot.isEmpty()) return false;
        long now = System.currentTimeMillis();
        return snapshot.stream().allMatch(k -> keyBlockedUntil.getOrDefault(k, 0L) > now);
    }

    /**
     * What DTDD said was left of a key's quota with its last answer ({@code X-RateLimit-Remaining-*}), null when it
     * didn't say; {@code at} when it said it (epoch ms).
     */
    public record Quota(Integer remainingMinute, Integer remainingMonth, long at) {}

    /**
     * Remembers what DTDD said was left of {@code key}'s quota. Nothing left this minute: the key rests until the
     * next one instead of asking for a 429.
     */
    public void onQuota(String key, Integer remainingMinute, Integer remainingMonth) {
        if (remainingMinute == null && remainingMonth == null) return;
        long now = System.currentTimeMillis();
        quotas.put(key, new Quota(remainingMinute, remainingMonth, now));
        if (remainingMinute != null && remainingMinute <= 0) {
            keyBlockedUntil.merge(key, now + 60_000L, Math::max);
        }
    }

    /** Each key's last known quota (keys DTDD never answered for are absent). */
    public Map<String, Quota> getQuotas() {
        return Map.copyOf(quotas);
    }

    /**
     * A key usable now that keeps more than {@code monthlyReserve} requests of its month (one whose month is unknown
     * counts as keeping them), round robin; empty when none is — for bulk work, which must leave the reserve to the
     * detection. Unlike {@link #nextKey}, never a blocked one.
     */
    public Optional<String> nextKeyAbove(int monthlyReserve) {
        long now = System.currentTimeMillis();
        List<String> available = keys.stream()
            .filter(k -> aboveReserve(k, monthlyReserve))
            .filter(k -> keyBlockedUntil.getOrDefault(k, 0L) <= now)
            .toList();
        if (available.isEmpty()) return Optional.empty();
        return Optional.of(available.get(Math.floorMod(counter.getAndIncrement(), available.size())));
    }

    /**
     * When a key that keeps more than {@code monthlyReserve} requests of its month is usable (epoch ms, now or
     * earlier when one already is); empty when no key keeps them: the month is spent down to the reserve.
     */
    public Optional<Long> nextUsableAt(int monthlyReserve) {
        return keys.stream()
            .filter(k -> aboveReserve(k, monthlyReserve))
            .map(k -> keyBlockedUntil.getOrDefault(k, 0L))
            .min(Long::compare);
    }

    /** How many keys keep more than {@code monthlyReserve} requests of their month, blocked or not. */
    public int keysAbove(int monthlyReserve) {
        return (int) keys.stream().filter(k -> aboveReserve(k, monthlyReserve)).count();
    }

    private boolean aboveReserve(String key, int monthlyReserve) {
        Quota quota = quotas.get(key);
        return quota == null || quota.remainingMonth() == null || quota.remainingMonth() > monthlyReserve;
    }

    public void onKeyRateLimited(String key, int retryAfterSeconds) {
        keyBlockedUntil.put(key, System.currentTimeMillis() + retryAfterSeconds * 1000L);
        meterRegistry.counter("catapult.external.rate_limited", "api", "dtdd", "scope", "key").increment();
        String masked = key.length() > 8
            ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
            : "…";
        log.warn("DTDD key {} rate limited for {}s", masked, retryAfterSeconds);
    }
}
