package fr.enimaloc.catapult.service.enrichment;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-IP sliding window that caps the upstream calls a public caller can trigger. */
class IpFetchBudgetsTest {

    private final MutableClock clock = new MutableClock();
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private final IpFetchBudgets budgets = new IpFetchBudgets(properties, clock);

    @Test
    void tenthCallPasses_eleventhRefused() {
        FetchBudget budget = budgets.forIp("203.0.113.7");
        for (int i = 0; i < 10; i++) {
            assertThat(budget.tryAcquire()).as("call %d", i + 1).isTrue();
        }
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void otherIp_hasItsOwnBudget() {
        FetchBudget first = budgets.forIp("203.0.113.7");
        for (int i = 0; i < 10; i++) {
            first.tryAcquire();
        }
        assertThat(first.tryAcquire()).isFalse();
        assertThat(budgets.forIp("203.0.113.8").tryAcquire()).isTrue();
    }

    @Test
    void windowSlides_afterOneMinute() {
        FetchBudget budget = budgets.forIp("203.0.113.7");
        for (int i = 0; i < 10; i++) {
            budget.tryAcquire();
        }
        assertThat(budget.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(61));
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void retryAfter_isTimeUntilOldestCallLeavesWindow() {
        FetchBudget budget = budgets.forIp("203.0.113.7");
        budget.tryAcquire();
        clock.advance(Duration.ofSeconds(20));
        for (int i = 0; i < 9; i++) {
            budget.tryAcquire();
        }
        clock.advance(Duration.ofSeconds(15));
        assertThat(budget.tryAcquire()).isFalse();
        // the oldest call is 35 s old, so it leaves the window in 25 s
        assertThat(budget.retryAfter()).isEqualTo(Duration.ofSeconds(25));
    }

    @Test
    void idleIps_areEvicted() {
        budgets.forIp("203.0.113.7").tryAcquire();
        budgets.forIp("203.0.113.8").tryAcquire();
        assertThat(budgets.trackedIps()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(61));
        budgets.evictIdle();
        assertThat(budgets.trackedIps()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-21T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }
}
