package fr.enimaloc.catapult.service.enrichment.riot;

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

class RiotRateLimiterTest {

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

    private RiotRateLimiter limiter() {
        return new RiotRateLimiter(properties, clock, duration -> {
            slept.add(duration);
            advance(duration);
        });
    }

    @Test
    void closeCallsWait_insteadOfBeingRefused() {
        RiotRateLimiter limiter = limiter();

        assertThat(limiter.acquire("k@euw1")).isTrue();
        assertThat(limiter.acquire("k@euw1")).isTrue();

        assertThat(slept).containsExactly(Duration.ofMillis(50));
    }

    @Test
    void eachKeyAndHost_hasItsOwnBucket() {
        RiotRateLimiter limiter = limiter();

        assertThat(limiter.acquire("k@euw1")).isTrue();
        assertThat(limiter.acquire("k@na1")).isTrue();
        assertThat(limiter.acquire("other@euw1")).isTrue();

        assertThat(slept).isEmpty();
    }

    @Test
    void theTwoMinuteWindow_refuses_thenFrees() {
        properties.getRiot().setPerTwoMinutes(3);
        RiotRateLimiter limiter = limiter();

        assertThat(limiter.acquire("k@euw1")).isTrue();
        assertThat(limiter.acquire("k@euw1")).isTrue();
        assertThat(limiter.acquire("k@euw1")).isTrue();
        assertThat(limiter.acquire("k@euw1")).isFalse();

        advance(Duration.ofMinutes(2));
        assertThat(limiter.acquire("k@euw1")).isTrue();
    }

    @Test
    void aSlotTooFarAway_isRefused() {
        properties.getRiot().setPerSecond(1);
        RiotRateLimiter limiter = new RiotRateLimiter(properties, clock, duration -> slept.add(duration));

        assertThat(limiter.acquire("k@euw1")).isTrue();
        assertThat(limiter.acquire("k@euw1")).isTrue();
        // the third slot is two seconds away: beyond the longest wait
        assertThat(limiter.acquire("k@euw1")).isFalse();
    }

    @Test
    void aPause_refusesTheBucketOnly_untilItIsOver() {
        RiotRateLimiter limiter = limiter();
        limiter.pause("k@euw1", Duration.ofSeconds(30));

        assertThat(limiter.paused("k@euw1")).isTrue();
        assertThat(limiter.acquire("k@euw1")).isFalse();
        assertThat(limiter.acquire("k@na1")).isTrue();

        advance(Duration.ofSeconds(30));
        assertThat(limiter.acquire("k@euw1")).isTrue();
    }

    @Test
    void theAppRateLimitHeader_setsTheLimits_withAMargin() {
        RiotRateLimiter limiter = limiter();

        limiter.learn("k@euw1", "500:10,30000:600");

        // 450 per 10 s spaced, 27 000 per 10 min
        assertThat(limiter.describe("k@euw1")).isEqualTo("22ms/27000per600s");
        assertThat(limiter.describe("k@na1")).isEqualTo("50ms/100per120s");
    }

    @Test
    void anUnreadableHeader_changesNothing() {
        RiotRateLimiter limiter = limiter();
        for (String header : new String[]{null, "", "abc", "20:1,x:120", "0:1", "20:1:3", "20:-1", "20:999999"}) {
            limiter.learn("k@euw1", header);
        }
        assertThat(limiter.describe("k@euw1")).isEqualTo("50ms/100per120s");
    }

    @Test
    void theWaitHappensOutsideTheLock() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RiotRateLimiter blocking = new RiotRateLimiter(properties, clock, duration -> {
            sleeping.countDown();
            release.await();
        });
        assertThat(blocking.acquire("k@euw1")).isTrue();
        Thread waiter = Thread.ofVirtual().start(() -> blocking.acquire("k@euw1"));
        assertThat(sleeping.await(2, TimeUnit.SECONDS)).isTrue();

        // while the first waiter sleeps, the bucket can still be paused and read
        blocking.pause("k@euw1", Duration.ofSeconds(1));
        assertThat(blocking.paused("k@euw1")).isTrue();

        release.countDown();
        waiter.join(2000);
    }

    @Test
    void anInterruptedWait_isRefused() throws Exception {
        RiotRateLimiter interrupted = new RiotRateLimiter(properties, clock, duration -> {
            throw new InterruptedException();
        });
        assertThat(interrupted.acquire("k@euw1")).isTrue();
        assertThat(interrupted.acquire("k@euw1")).isFalse();
        assertThat(Thread.interrupted()).isTrue();
    }
}
