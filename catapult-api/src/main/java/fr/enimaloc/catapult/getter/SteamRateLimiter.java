package fr.enimaloc.catapult.getter;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

// Per-key token bucket: each API key (shared or personal) gets its own permit pool.
// On a 429 response for a key, that key's permits are drained and blocked until
// the Retry-After deadline. conservativePause drains all keys simultaneously.
@Slf4j
@Component
@Profile("!mock")
@ConditionalOnBooleanProperty("steam.enabled")
public class SteamRateLimiter {

    private final int permitsPerWindow;
    private final long maxWaitMs;
    private final long windowMs;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, Semaphore> keyPermits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> keyBlockedUntil = new ConcurrentHashMap<>();
    private volatile long globalBlockedUntil = 0;

    public SteamRateLimiter(
        @Value("${steam.rate-limit.permits-per-window:3}") int permitsPerWindow,
        @Value("${steam.rate-limit.max-wait-ms:2000}") long maxWaitMs,
        @Value("${steam.rate-limit.window-ms:5000}") long windowMs,
        MeterRegistry meterRegistry
    ) {
        this.permitsPerWindow = permitsPerWindow;
        this.maxWaitMs = maxWaitMs;
        this.windowMs = windowMs;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Acquire a permit for the given key before making a Steam API call.
     * Fast-fails if the key or the global penalty window is active.
     * Otherwise waits up to maxWaitMs for a token to become available.
     */
    public boolean acquire(String key) {
        long now = System.currentTimeMillis();
        if (now < globalBlockedUntil || now < keyBlockedUntil.getOrDefault(key, 0L)) return false;
        try {
            return semaphoreFor(key).tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Acquire a permit by blocking indefinitely (for background tasks on virtual threads).
     */
    public boolean acquireBlocking(String key) {
        try {
            semaphoreFor(key).acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Called when a specific key receives a 429. Drains that key's permits
     * and blocks it until the Retry-After deadline.
     */
    public void onRateLimitResponse(String key, int retryAfterSeconds) {
        semaphoreFor(key).drainPermits();
        keyBlockedUntil.put(key, System.currentTimeMillis() + retryAfterSeconds * 1000L);
        meterRegistry.counter("catapult.external.rate_limited", "api", "steam", "scope", "key").increment();
        log.warn("Steam key rate limited — pausing for {}s", retryAfterSeconds);
    }

    /**
     * Called when conservativePause is enabled: drains all key permits globally.
     */
    public void blockAll(int retryAfterSeconds) {
        globalBlockedUntil = System.currentTimeMillis() + retryAfterSeconds * 1000L;
        keyPermits.values().forEach(Semaphore::drainPermits);
        meterRegistry.counter("catapult.external.rate_limited", "api", "steam", "scope", "global").increment();
        log.warn("Steam API global rate limit — pausing all keys for {}s", retryAfterSeconds);
    }

    public boolean isBlocked() {
        return System.currentTimeMillis() < globalBlockedUntil;
    }

    public long millisUntilAvailable() {
        long remaining = globalBlockedUntil - System.currentTimeMillis();
        if (remaining <= 0) return 0;
        return remaining + windowMs;
    }

    @Scheduled(fixedRateString = "${steam.rate-limit.window-ms:5000}")
    void replenish() {
        long now = System.currentTimeMillis();
        if (now < globalBlockedUntil) return;
        keyPermits.forEach((key, semaphore) -> {
            if (now >= keyBlockedUntil.getOrDefault(key, 0L)) {
                int deficit = permitsPerWindow - semaphore.availablePermits();
                if (deficit > 0) semaphore.release(deficit);
            }
        });
    }

    private Semaphore semaphoreFor(String key) {
        return keyPermits.computeIfAbsent(key, k -> new Semaphore(permitsPerWindow, true));
    }
}
