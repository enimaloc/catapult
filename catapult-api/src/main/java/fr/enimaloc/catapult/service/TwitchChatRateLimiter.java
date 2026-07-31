package fr.enimaloc.catapult.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

// Per-sender token bucket for Twitch Helix chat sends (mirrors SteamRateLimiter's approach).
// Twitch caps non-mod chat bots at 20 messages/30s per channel and mods at 100/30s; the bot
// account's mod status varies per channel, so the conservative non-mod window is the default.
// A 429 drains that sender's permits and blocks it until Twitch's own reset deadline.
@Slf4j
@Component
public class TwitchChatRateLimiter {

    private final int permitsPerWindow;
    private final long maxWaitMs;
    private final long windowMs;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, Semaphore> senderPermits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> senderBlockedUntil = new ConcurrentHashMap<>();

    public TwitchChatRateLimiter(
        @Value("${twitch.chat.rate-limit.permits-per-window:18}") int permitsPerWindow,
        @Value("${twitch.chat.rate-limit.max-wait-ms:3000}") long maxWaitMs,
        @Value("${twitch.chat.rate-limit.window-ms:30000}") long windowMs,
        MeterRegistry meterRegistry
    ) {
        this.permitsPerWindow = permitsPerWindow;
        this.maxWaitMs = maxWaitMs;
        this.windowMs = windowMs;
        this.meterRegistry = meterRegistry;
        meterRegistry.counter("catapult.external.rate_limited", "api", "twitch_chat", "scope", "sender");
        Gauge.builder("catapult.external.keys", this, TwitchChatRateLimiter::blockedSenderCount)
            .tag("api", "twitch_chat").tag("state", "blocked")
            .description("Twitch chat senders currently paused by rate limit")
            .register(meterRegistry);
    }

    public int blockedSenderCount() {
        long now = System.currentTimeMillis();
        return (int) senderBlockedUntil.values().stream().filter(until -> until > now).count();
    }

    /**
     * Acquire a permit for the given sender (bot id or streamer's own twitch id) before
     * posting to Helix. Fast-fails if that sender is currently paused from a prior 429,
     * otherwise waits up to maxWaitMs for a token to free up.
     */
    public boolean acquire(String senderId) {
        long now = System.currentTimeMillis();
        if (now < senderBlockedUntil.getOrDefault(senderId, 0L)) return false;
        try {
            return permitsFor(senderId).tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Called when a sender receives a 429 from Helix. Drains its permits and blocks it
     * until the deadline Twitch reported (or a conservative default if it reported none).
     */
    public void onRateLimitResponse(String senderId, long retryAfterSeconds) {
        permitsFor(senderId).drainPermits();
        senderBlockedUntil.put(senderId, System.currentTimeMillis() + retryAfterSeconds * 1000L);
        meterRegistry.counter("catapult.external.rate_limited", "api", "twitch_chat", "scope", "sender").increment();
        log.warn("[EventSub Chat] sender {} rate limited by Twitch — pausing for {}s", senderId, retryAfterSeconds);
    }

    @Scheduled(fixedRateString = "${twitch.chat.rate-limit.window-ms:30000}")
    void replenish() {
        long now = System.currentTimeMillis();
        senderPermits.forEach((senderId, semaphore) -> {
            if (now >= senderBlockedUntil.getOrDefault(senderId, 0L)) {
                int deficit = permitsPerWindow - semaphore.availablePermits();
                if (deficit > 0) semaphore.release(deficit);
            }
        });
    }

    private Semaphore permitsFor(String senderId) {
        return senderPermits.computeIfAbsent(senderId, k -> new Semaphore(permitsPerWindow, true));
    }
}
