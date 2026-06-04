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
    private final Semaphore semaphore;
    private volatile long blockedUntil = 0;

    public SteamRateLimiter(
        @Value("${steam.rate-limit.permits-per-window:3}") int permitsPerWindow,
        @Value("${steam.rate-limit.max-wait-ms:2000}") long maxWaitMs
    ) {
        this.permitsPerWindow = permitsPerWindow;
        this.maxWaitMs = maxWaitMs;
        this.semaphore = new Semaphore(permitsPerWindow, true);
    }

    /**
     * Acquire a permit before making a Steam API call.
     * Waits up to maxWaitMs for a token to become available.
     * Returns false if rate-limited and the timeout expires.
     */
    public boolean acquire() {
        try {
            return semaphore.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
