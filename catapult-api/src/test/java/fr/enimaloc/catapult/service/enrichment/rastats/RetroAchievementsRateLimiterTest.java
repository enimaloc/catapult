package fr.enimaloc.catapult.service.enrichment.rastats;

import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
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

    private final EnrichmentProperties properties = new EnrichmentProperties();
    private final RetroAchievementsRateLimiter limiter = new RetroAchievementsRateLimiter(properties, clock);

    @Test
    void raSmoothsToOnePerSecond() {
        FetchBudget budget = limiter.budget();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        advance(Duration.ofSeconds(1));
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void capsAtThirtyPerMinute() {
        FetchBudget budget = limiter.budget();
        for (int i = 0; i < 30; i++) {
            assertThat(budget.tryAcquire()).as("call %d", i + 1).isTrue();
            advance(Duration.ofSeconds(1));
        }
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
