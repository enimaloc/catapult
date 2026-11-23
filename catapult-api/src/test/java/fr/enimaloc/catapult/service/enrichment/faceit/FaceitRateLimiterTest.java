package fr.enimaloc.catapult.service.enrichment.faceit;

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

class FaceitRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T10:00:00Z"));
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

    private FaceitRateLimiter limiter() {
        return new FaceitRateLimiter(properties, clock, duration -> {
            slept.add(duration);
            advance(duration);
        });
    }

    @Test
    void closeCallsWaitInsteadOfBeingRefused() {
        FetchBudget budget = limiter().budget();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(slept).containsExactly(Duration.ofMillis(100), Duration.ofMillis(100));
    }

    @Test
    void waitHappensOutsideTheLock() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FaceitRateLimiter blocking = new FaceitRateLimiter(properties, clock, duration -> {
            sleeping.countDown();
            release.await();
        });
        FetchBudget budget = blocking.budget();
        assertThat(budget.tryAcquire()).isTrue();
        Thread waiter = Thread.ofVirtual().start(budget::tryAcquire);
        assertThat(sleeping.await(5, TimeUnit.SECONDS)).isTrue();
        // the waiting caller does not hold the lock: the others still get an answer
        assertThat(blocking.paused()).isFalse();
        assertThat(budget.retryAfter()).isPositive();
        release.countDown();
        waiter.join(5000);
        assertThat(waiter.isAlive()).isFalse();
    }

    @Test
    void hourlyCapRefuses() {
        properties.getFaceit().setPerHour(3);
        FetchBudget budget = limiter().budget();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.retryAfter()).isGreaterThan(Duration.ofMinutes(59));
        advance(Duration.ofHours(1));
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void waitBeyondOneSecondIsRefused() {
        properties.getFaceit().setPerSecond(1);
        // a sleeper that does not move the clock: three callers at the same instant
        FaceitRateLimiter limiter = new FaceitRateLimiter(properties, clock, slept::add);
        FetchBudget budget = limiter.budget();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(slept).containsExactly(Duration.ofSeconds(1));
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void pauseRefusesUntilItEnds() {
        FaceitRateLimiter limiter = limiter();
        FetchBudget budget = limiter.budget();
        limiter.pause(Duration.ofSeconds(60));
        limiter.pause(Duration.ofSeconds(10));
        assertThat(limiter.paused()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.retryAfter()).isEqualTo(Duration.ofSeconds(60));
        advance(Duration.ofSeconds(61));
        assertThat(limiter.paused()).isFalse();
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void anInterruptedWait_refusesTheCall() {
        FaceitRateLimiter limiter = new FaceitRateLimiter(properties, clock, d -> {
            throw new InterruptedException();
        });
        FetchBudget budget = limiter.budget();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(Thread.interrupted()).isTrue();
    }
}
