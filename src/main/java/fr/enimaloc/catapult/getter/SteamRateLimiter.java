package fr.enimaloc.catapult.getter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

// Token bucket: permitsPerWindow tokens replenished every window-ms milliseconds.
// On a 429 response, all tokens are drained and replenishment is blocked until
// the Retry-After deadline, preventing further requests during the penalty window.
@Slf4j
@Component
@Profile("!mock")
@ConditionalOnBooleanProperty("steam.enabled")
public class SteamRateLimiter {

    // 200 req/5min ≈ 0.67 req/s; 3 permits per 5s window stays safely under that.
    private final int permitsPerWindow;
    private final long maxWaitMs;
    private final long windowMs;
    private final Semaphore semaphore;
    private volatile long blockedUntil = 0;

    public SteamRateLimiter(
        @Value("${steam.rate-limit.permits-per-window:3}") int permitsPerWindow,
        @Value("${steam.rate-limit.max-wait-ms:2000}") long maxWaitMs,
        @Value("${steam.rate-limit.window-ms:5000}") long windowMs
    ) {
        this.permitsPerWindow = permitsPerWindow;
        this.maxWaitMs = maxWaitMs;
        this.windowMs = windowMs;
        this.semaphore = new Semaphore(permitsPerWindow, true);
    }

    /**
     * Acquire a permit before making a Steam API call.
     * Fast-fails immediately if within the 429 penalty window.
     * Otherwise waits up to maxWaitMs for a token to become available.
     */
    public boolean acquire() {
        if (System.currentTimeMillis() < blockedUntil) return false;
        try {
            return semaphore.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Acquire a permit by blocking indefinitely (for background tasks on virtual threads).
     * Used for background operations like library preload that can afford to block.
     */
    public boolean acquireBlocking() {
        try {
            semaphore.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Returns how long (ms) a background task should sleep before calling acquire(),
     * accounting for the current penalty window and one replenishment cycle.
     * Returns 0 when tokens are likely available immediately.
     */
    public long millisUntilAvailable() {
        long remaining = blockedUntil - System.currentTimeMillis();
        if (remaining <= 0) return 0;
        // Wait through the penalty window + one replenishment cycle to ensure tokens are added
        return remaining + windowMs;
    }

    public boolean isBlocked() {
        return System.currentTimeMillis() < blockedUntil;
    }

    /**
     * Called when Steam returns a 429. Drains all tokens and blocks
     * replenishment until the Retry-After deadline.
     */
    public void onRateLimitResponse(int retryAfterSeconds) {
        semaphore.drainPermits();
        blockedUntil = System.currentTimeMillis() + (retryAfterSeconds * 1000L);
        log.warn("Steam API rate limited — pausing for {}s", retryAfterSeconds);
    }

    @Scheduled(fixedRateString = "${steam.rate-limit.window-ms:5000}")
    void replenish() {
        if (System.currentTimeMillis() < blockedUntil) return;
        int deficit = permitsPerWindow - semaphore.availablePermits();
        if (deficit > 0) {
            semaphore.release(deficit);
        }
    }
}
