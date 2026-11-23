package fr.enimaloc.catapult.service.enrichment.xboxstats;

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

class XboxStatsRateLimiterTest {

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
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    private XboxStatsRateLimiter limiter(int total, int perMember) {
        properties.getXboxStats().setPerMinute(total);
        properties.getXboxStats().setPerMemberPerMinute(perMember);
        return new XboxStatsRateLimiter(properties, clock);
    }

    @Test
    void xboxPerMemberCapCountsInTotal() {
        XboxStatsRateLimiter limiter = limiter(3, 2);
        assertThat(limiter.forMember(a).tryAcquire()).isTrue();
        assertThat(limiter.forMember(a).tryAcquire()).isTrue();
        assertThat(limiter.forMember(a).tryAcquire()).isFalse();
        assertThat(limiter.forMember(b).tryAcquire()).isTrue();
        assertThat(limiter.forMember(b).tryAcquire()).isFalse();
    }

    @Test
    void windowSlides() {
        XboxStatsRateLimiter limiter = limiter(3, 1);
        assertThat(limiter.forMember(a).tryAcquire()).isTrue();
        assertThat(limiter.forMember(a).tryAcquire()).isFalse();
        advance(Duration.ofSeconds(61));
        assertThat(limiter.forMember(a).tryAcquire()).isTrue();
    }

    @Test
    void xboxPauseIsPerMember() {
        XboxStatsRateLimiter limiter = limiter(120, 10);
        limiter.pause(a, Duration.ofSeconds(60));
        FetchBudget mine = limiter.forMember(a);
        assertThat(mine.tryAcquire()).isFalse();
        assertThat(limiter.forMember(b).tryAcquire()).isTrue();
        assertThat(mine.retryAfter()).isBetween(Duration.ofSeconds(59), Duration.ofSeconds(60));
        advance(Duration.ofSeconds(61));
        assertThat(mine.tryAcquire()).isTrue();
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        XboxStatsRateLimiter limiter = limiter(120, 1);
        limiter.forMember(a).tryAcquire();
        advance(Duration.ofSeconds(59).plusMillis(900));
        assertThat(limiter.forMember(a).retryAfter()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void retryAfterWaitsForTheTotalWindowToo() {
        XboxStatsRateLimiter limiter = limiter(1, 5);
        limiter.forMember(a).tryAcquire();
        advance(Duration.ofSeconds(10));
        assertThat(limiter.forMember(b).tryAcquire()).isFalse();
        assertThat(limiter.forMember(b).retryAfter()).isEqualTo(Duration.ofSeconds(50));
    }

    @Test
    void evictIdleKeepsTheLimitsCorrect() {
        XboxStatsRateLimiter limiter = limiter(120, 1);
        limiter.forMember(a).tryAcquire();
        limiter.pause(b, Duration.ofSeconds(10));
        advance(Duration.ofSeconds(61));
        limiter.evictIdle();
        assertThat(limiter.forMember(a).tryAcquire()).isTrue();
        assertThat(limiter.forMember(b).tryAcquire()).isTrue();
    }
}
