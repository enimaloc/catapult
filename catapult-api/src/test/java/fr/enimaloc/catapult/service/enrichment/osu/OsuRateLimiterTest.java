package fr.enimaloc.catapult.service.enrichment.osu;

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

class OsuRateLimiterTest {

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

    private OsuRateLimiter limiter() {
        return new OsuRateLimiter(properties, clock, duration -> {
            slept.add(duration);
            advance(duration);
        });
    }

    @Test
    void theDefault_isFiftyAMinute_spacedBy1200ms() {
        assertThat(properties.osuPerMinute()).isEqualTo(50);
        FetchBudget budget = limiter().budget();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(slept).containsExactly(Duration.ofMillis(1200));
    }

    @Test
    void waitHappensOutsideTheLock() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OsuRateLimiter blocking = new OsuRateLimiter(properties, clock, duration -> {
            sleeping.countDown();
            release.await();
        });
        FetchBudget budget = blocking.budget();
        assertThat(budget.tryAcquire()).isTrue();
        Thread waiter = Thread.ofVirtual().start(budget::tryAcquire);
        assertThat(sleeping.await(5, TimeUnit.SECONDS)).isTrue();
        // another caller is not held by the sleeping one
        assertThat(blocking.paused()).isFalse();
        assertThat(budget.retryAfter()).isPositive();
        release.countDown();
        waiter.join(5000);
        assertThat(waiter.isAlive()).isFalse();
    }

    @Test
    void theMinuteCapRefuses() {
        properties.getOsu().setPerMinute(3);
        FetchBudget budget = limiter().budget();
        // three a minute: one every 20 s
        assertThat(budget.tryAcquire()).isTrue();
        advance(Duration.ofSeconds(20));
        assertThat(budget.tryAcquire()).isTrue();
        advance(Duration.ofSeconds(20));
        assertThat(budget.tryAcquire()).isTrue();
        advance(Duration.ofSeconds(19));
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.retryAfter()).isPositive();
        advance(Duration.ofSeconds(2));
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void aWaitBeyondTheBoundIsRefused() {
        // a sleeper that does not move the clock: callers at the same instant queue up 1.2 s apart
        OsuRateLimiter limiter = new OsuRateLimiter(properties, clock, slept::add);
        FetchBudget budget = limiter.budget();
        for (int i = 0; i < 5; i++) {
            assertThat(budget.tryAcquire()).isTrue();
        }
        assertThat(slept.getLast()).isEqualTo(Duration.ofMillis(4800));
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void pauseRefusesUntilItEnds() {
        OsuRateLimiter limiter = limiter();
        FetchBudget budget = limiter.budget();
        limiter.pause(Duration.ofSeconds(60));
        assertThat(limiter.paused()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.retryAfter()).isEqualTo(Duration.ofSeconds(60));
        limiter.pause(Duration.ofSeconds(10));
        assertThat(budget.retryAfter()).isEqualTo(Duration.ofSeconds(60));
        advance(Duration.ofSeconds(61));
        assertThat(limiter.paused()).isFalse();
        assertThat(budget.tryAcquire()).isTrue();
    }
}
