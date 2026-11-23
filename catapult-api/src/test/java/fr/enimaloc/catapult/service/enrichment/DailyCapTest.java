package fr.enimaloc.catapult.service.enrichment;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DailyCapTest {

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void refusesBeyondCapThenResetsAtUtcMidnight() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-21T23:00:00Z"));
        DailyCap cap = new DailyCap(clock);
        FetchBudget budget = cap.budget(EnrichmentSource.OPENCRITIC, 2);

        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();

        clock.advance(Duration.ofMinutes(61));
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void countsPerSource() {
        DailyCap cap = new DailyCap(new MutableClock(Instant.parse("2026-11-21T10:00:00Z")));
        assertThat(cap.budget(EnrichmentSource.OPENCRITIC, 1).tryAcquire()).isTrue();
        assertThat(cap.budget(EnrichmentSource.PRICES, 1).tryAcquire()).isTrue();
        assertThat(cap.budget(EnrichmentSource.OPENCRITIC, 1).tryAcquire()).isFalse();
    }

    @Test
    void refusal_retriesAfterTheNextUtcMidnight() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-21T22:30:15Z"));
        FetchBudget budget = new DailyCap(clock).budget(EnrichmentSource.OPENCRITIC, 0);

        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.retryAfter()).isEqualTo(Duration.ofMinutes(89).plusSeconds(45));
    }
}
