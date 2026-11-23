package fr.enimaloc.catapult.service.enrichment.supercell;

import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
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

class SupercellRateLimiterTest {

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

    private SupercellRateLimiter limiter() {
        return new SupercellRateLimiter(properties, clock, duration -> {
            slept.add(duration);
            advance(duration);
        });
    }

    @Test
    void closeCallsWait_insteadOfBeingRefused() {
        SupercellRateLimiter limiter = limiter();

        assertThat(limiter.acquire("key:a")).isTrue();
        assertThat(limiter.acquire("key:a")).isTrue();

        assertThat(slept).containsExactly(Duration.ofMillis(100));
    }

    @Test
    void eachKey_hasItsOwnBucket() {
        SupercellRateLimiter limiter = limiter();

        assertThat(limiter.acquire("key:a")).isTrue();
        assertThat(limiter.acquire("key:b")).isTrue();

        assertThat(slept).isEmpty();
    }

    @Test
    void theMinuteWindow_refuses_thenFrees() {
        properties.getSupercell().setPerMinute(3);
        SupercellRateLimiter limiter = limiter();

        assertThat(limiter.acquire("key:a")).isTrue();
        assertThat(limiter.acquire("key:a")).isTrue();
        assertThat(limiter.acquire("key:a")).isTrue();
        assertThat(limiter.acquire("key:a")).isFalse();

        advance(Duration.ofMinutes(1));
        assertThat(limiter.acquire("key:a")).isTrue();
    }

    @Test
    void aSlotTooFarAway_isRefused() {
        properties.getSupercell().setPerSecond(1);
        SupercellRateLimiter limiter = new SupercellRateLimiter(properties, clock, duration -> slept.add(duration));

        assertThat(limiter.acquire("key:a")).isTrue();
        assertThat(limiter.acquire("key:a")).isTrue();
        // the third slot is two seconds away: beyond the longest wait
        assertThat(limiter.acquire("key:a")).isFalse();
    }

    @Test
    void aPause_refusesTheBucketOnly_untilItIsOver_andIsNeverShortened() {
        SupercellRateLimiter limiter = limiter();
        limiter.pause("key:a", Duration.ofSeconds(60));
        limiter.pause("key:a", Duration.ofSeconds(5));

        assertThat(limiter.paused("key:a")).isTrue();
        assertThat(limiter.acquire("key:a")).isFalse();
        assertThat(limiter.acquire("key:b")).isTrue();

        advance(Duration.ofSeconds(10));
        assertThat(limiter.paused("key:a")).isTrue();
        advance(Duration.ofSeconds(50));
        assertThat(limiter.acquire("key:a")).isTrue();
    }

    @Test
    void theWaitHappensOutsideTheLock() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SupercellRateLimiter blocking = new SupercellRateLimiter(properties, clock, duration -> {
            sleeping.countDown();
            release.await();
        });
        assertThat(blocking.acquire("key:a")).isTrue();
        Thread waiter = Thread.ofVirtual().start(() -> blocking.acquire("key:a"));
        assertThat(sleeping.await(2, TimeUnit.SECONDS)).isTrue();

        // while the first waiter sleeps, the bucket can still be paused and read
        blocking.pause("key:a", Duration.ofSeconds(1));
        assertThat(blocking.paused("key:a")).isTrue();

        release.countDown();
        waiter.join(2000);
    }

    @Test
    void anInterruptedWait_isRefused() {
        SupercellRateLimiter interrupted = new SupercellRateLimiter(properties, clock, duration -> {
            throw new InterruptedException();
        });
        assertThat(interrupted.acquire("key:a")).isTrue();
        assertThat(interrupted.acquire("key:a")).isFalse();
        assertThat(Thread.interrupted()).isTrue();
    }
}
