package fr.enimaloc.catapult.service.enrichment.speedrun;

import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SpeedrunRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private SpeedrunRateLimiter limiter(int cap) {
        EnrichmentProperties props = new EnrichmentProperties();
        props.getSpeedrun().setPerMinute(cap);
        return new SpeedrunRateLimiter(props, clock);
    }

    private void advance(Duration d) {
        now.set(now.get().plus(d));
    }

    @Test
    void capsPerMinuteThenSlides() {
        SpeedrunRateLimiter l = limiter(3);
        assertThat(l.member().tryAcquire()).isTrue();
        assertThat(l.member().tryAcquire()).isTrue();
        assertThat(l.member().tryAcquire()).isTrue();
        assertThat(l.member().tryAcquire()).isFalse();
        assertThat(l.member().retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1));
        advance(Duration.ofSeconds(61));
        assertThat(l.member().tryAcquire()).isTrue();
    }

    @Test
    void publicShareIsHalf() {
        SpeedrunRateLimiter l = limiter(4);
        assertThat(l.publicShare().tryAcquire()).isTrue();
        assertThat(l.publicShare().tryAcquire()).isTrue();
        assertThat(l.publicShare().tryAcquire()).isFalse();
        assertThat(l.publicShare().retryAfter()).isPositive();
        assertThat(l.member().tryAcquire()).isTrue();
        assertThat(l.member().tryAcquire()).isTrue();
        assertThat(l.member().tryAcquire()).isFalse();
    }

    @Test
    void publicShareAlsoRespectsTheWholeCap() {
        SpeedrunRateLimiter l = limiter(4);
        l.member().tryAcquire();
        l.member().tryAcquire();
        l.member().tryAcquire();
        assertThat(l.publicShare().tryAcquire()).isTrue();
        assertThat(l.publicShare().tryAcquire()).isFalse();
    }

    @Test
    void status420PausesEveryCall() {
        SpeedrunRateLimiter l = limiter(10);
        assertThat(l.paused()).isFalse();
        l.pause(Duration.ofSeconds(60));
        assertThat(l.paused()).isTrue();
        assertThat(l.member().tryAcquire()).isFalse();
        assertThat(l.publicShare().tryAcquire()).isFalse();
        assertThat(l.member().retryAfter()).isEqualTo(Duration.ofSeconds(60));
        assertThat(l.publicShare().retryAfter()).isEqualTo(Duration.ofSeconds(60));
        advance(Duration.ofSeconds(60));
        assertThat(l.paused()).isFalse();
        assertThat(l.member().tryAcquire()).isTrue();
        assertThat(l.publicShare().tryAcquire()).isTrue();
    }

    @Test
    void defaultsAndSourcesAreWired() {
        EnrichmentProperties props = new EnrichmentProperties();
        assertThat(props.speedrunPerMinute()).isEqualTo(60);
        assertThat(props.source(EnrichmentSource.SPEEDRUN_GAME).ttl()).isEqualTo(Duration.ofDays(30));
        assertThat(props.source(EnrichmentSource.SPEEDRUN_RECORDS).ttl()).isEqualTo(Duration.ofHours(1));
        assertThat(props.source(EnrichmentSource.SPEEDRUN_USER).ttl()).isEqualTo(Duration.ofDays(7));
        assertThat(props.source(EnrichmentSource.SPEEDRUN_PBS).ttl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.source(EnrichmentSource.SPEEDRUN_PENDING).ttl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(EnrichmentSource.SPEEDRUN_PBS.key()).isEqualTo("speedrun-pbs");
        assertThat(new SpeedrunRateLimiter(props).member().tryAcquire()).isTrue();
    }
}
