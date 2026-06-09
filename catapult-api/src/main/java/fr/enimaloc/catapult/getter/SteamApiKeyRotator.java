package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
@ConditionalOnBooleanProperty("steam.enabled")
public class SteamApiKeyRotator {

    private final SteamApiKeyRepository repository;
    private final SteamRateLimiter rateLimiter;
    private final boolean conservativePause;
    private final Map<String, Long> keyBlockedUntil = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile List<String> keys = List.of();

    public SteamApiKeyRotator(
            SteamApiKeyRepository repository,
            SteamRateLimiter rateLimiter,
            @Value("${steam.rate-limit.conservative-pause:false}") boolean conservativePause) {
        this.repository = repository;
        this.rateLimiter = rateLimiter;
        this.conservativePause = conservativePause;
        refreshKeys();
    }

    public void refreshKeys() {
        this.keys = repository.findByExclusiveFalse()
            .stream()
            .map(SteamApiKeyEntry::getApiKey)
            .toList();
        log.info("Steam API key pool refreshed: {} key(s)", keys.size());
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
        String masked = key.length() > 8
            ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
            : "…";
        log.warn("Steam key {} rate limited for {}s", masked, retryAfterSeconds);
        if (conservativePause) {
            rateLimiter.onRateLimitResponse(retryAfterSeconds);
        }
    }
}
