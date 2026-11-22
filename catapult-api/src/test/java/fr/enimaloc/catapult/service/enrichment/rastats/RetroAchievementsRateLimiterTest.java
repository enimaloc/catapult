package fr.enimaloc.catapult.service.enrichment.rastats;

import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RetroAchievementsRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-22T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private void advance(Duration by) {
        now.set(now.get().plus(by));
    }

    private final List<Duration> slept = new ArrayList<>();
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private final RetroAchievementsRateLimiter limiter = new RetroAchievementsRateLimiter(properties, clock,
            duration -> {
                slept.add(duration);
                advance(duration);
            });

    @Test
    void raSmoothsToOnePerSecond() {
        FetchBudget budget = limiter.budget();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(slept).isEmpty();
        // back-to-back: the second call waits for its slot instead of being refused
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(slept).containsExactly(Duration.ofSeconds(1));
        advance(Duration.ofSeconds(3));
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(slept).hasSize(1);
    }

    @Test
    void aSlotTooFarAwayIsRefused() {
        RetroAchievementsRateLimiter noSleep = new RetroAchievementsRateLimiter(properties, clock, slept::add);
        FetchBudget budget = noSleep.budget();
        // nobody sleeps here: three callers at the same instant reserve now, +1 s, then +2 s (> 1.5 s: refused)
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(slept).containsExactly(Duration.ofSeconds(1));
        assertThat(budget.retryAfter()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void theWaitHoldsNoLock() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RetroAchievementsRateLimiter blocking = new RetroAchievementsRateLimiter(properties, clock, duration -> {
            sleeping.countDown();
            release.await();
        });
        FetchBudget budget = blocking.budget();
        assertThat(budget.tryAcquire()).isTrue();
        Thread waiter = Thread.ofVirtual().start(budget::tryAcquire);
        assertThat(sleeping.await(5, TimeUnit.SECONDS)).isTrue();
        // while the waiter sleeps, the limiter still answers (no lock held during the wait)
        assertThat(blocking.paused()).isFalse();
        assertThat(budget.retryAfter()).isEqualTo(Duration.ofSeconds(2));
        release.countDown();
        waiter.join(5000);
        assertThat(waiter.isAlive()).isFalse();
    }

    @Test
    void capsAtThirtyPerMinute() {
        FetchBudget budget = limiter.budget();
        for (int i = 0; i < 30; i++) {
            assertThat(budget.tryAcquire()).as("call %d", i + 1).isTrue();
            advance(Duration.ofSeconds(1));
        }
        assertThat(slept).as("calls a second apart never wait").isEmpty();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.retryAfter()).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(30));
        advance(Duration.ofSeconds(31));
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void pauseRefusesEverythingUntilItEnds() {
        FetchBudget budget = limiter.budget();
        limiter.pause(Duration.ofSeconds(60));
        assertThat(limiter.paused()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.retryAfter()).isEqualTo(Duration.ofSeconds(60));
        advance(Duration.ofSeconds(61));
        assertThat(limiter.paused()).isFalse();
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void shorterPauseDoesNotShortenALongerOne() {
        limiter.pause(Duration.ofSeconds(60));
        limiter.pause(Duration.ofSeconds(5));
        advance(Duration.ofSeconds(30));
        assertThat(limiter.paused()).isTrue();
    }
}
