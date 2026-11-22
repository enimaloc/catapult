package fr.enimaloc.catapult.service.enrichment.bungie;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BungieRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-22T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final List<Duration> slept = new ArrayList<>();
    private final EnrichmentProperties properties = new EnrichmentProperties();

    private void advance(Duration by) {
        now.set(now.get().plus(by));
    }

    private BungieRateLimiter limiter() {
        return new BungieRateLimiter(properties, clock, duration -> {
            slept.add(duration);
            advance(duration);
        });
    }

    @Test
    void closeCallsWaitInsteadOfBeingRefused() {
        FetchBudget budget = limiter().budget();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(slept).containsExactly(Duration.ofMillis(50));
    }

    @Test
    void waitHappensOutsideTheLock() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BungieRateLimiter blocking = new BungieRateLimiter(properties, clock, duration -> {
            sleeping.countDown();
            release.await();
        });
        FetchBudget budget = blocking.budget();
        assertThat(budget.tryAcquire()).isTrue();
        Thread waiter = Thread.ofVirtual().start(budget::tryAcquire);
        assertThat(sleeping.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(blocking.paused()).isFalse();
        assertThat(budget.retryAfter()).isPositive();
        release.countDown();
        waiter.join(5000);
        assertThat(waiter.isAlive()).isFalse();
    }

    @Test
    void hourlyCapRefuses() {
        properties.getBungie().setPerHour(3);
        BungieRateLimiter limiter = limiter();
        FetchBudget budget = limiter.budget();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.retryAfter()).isPositive();
        advance(Duration.ofHours(1));
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void waitBeyondOneSecondIsRefused() {
        properties.getBungie().setPerSecond(1);
        // a sleeper that does not move the clock: three callers at the same instant
        BungieRateLimiter limiter = new BungieRateLimiter(properties, clock, slept::add);
        FetchBudget budget = limiter.budget();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(slept).containsExactly(Duration.ofSeconds(1));
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void pauseRefusesUntilItEnds() {
        BungieRateLimiter limiter = limiter();
        FetchBudget budget = limiter.budget();
        limiter.pause(Duration.ofSeconds(60));
        assertThat(limiter.paused()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.retryAfter()).isEqualTo(Duration.ofSeconds(60));
        advance(Duration.ofSeconds(61));
        assertThat(limiter.paused()).isFalse();
        assertThat(budget.tryAcquire()).isTrue();
    }
}
