package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.DtddApiKeyEntry;
import fr.enimaloc.catapult.repository.DtddApiKeyRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@Profile("!mock")
@ConditionalOnBooleanProperty("dtdd.enabled")
public class DtddApiKeyRotator {

    private final DtddApiKeyRepository repository;
    private final MeterRegistry meterRegistry;
    private final Map<String, Long> keyBlockedUntil = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile List<String> keys = List.of();

    public DtddApiKeyRotator(DtddApiKeyRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        // Pré-enregistre le compteur pour qu'il soit exporté à 0 avant le premier événement
        meterRegistry.counter("catapult.external.rate_limited", "api", "dtdd", "scope", "key");
        Gauge.builder("catapult.external.keys", this, DtddApiKeyRotator::availableKeyCount)
            .tag("api", "dtdd").tag("state", "available")
            .description("Clés DTDD utilisables (pool partagé, hors rate limit)")
            .register(meterRegistry);
        Gauge.builder("catapult.external.keys", this, DtddApiKeyRotator::blockedKeyCount)
            .tag("api", "dtdd").tag("state", "blocked")
            .description("Clés DTDD bloquées par rate limit")
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

    public void onKeyRateLimited(String key, int retryAfterSeconds) {
        keyBlockedUntil.put(key, System.currentTimeMillis() + retryAfterSeconds * 1000L);
        meterRegistry.counter("catapult.external.rate_limited", "api", "dtdd", "scope", "key").increment();
        String masked = key.length() > 8
            ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
            : "…";
        log.warn("DTDD key {} rate limited for {}s", masked, retryAfterSeconds);
    }
}
