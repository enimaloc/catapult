package fr.enimaloc.catapult.service.enrichment;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-member sliding window that caps the upstream calls a member can trigger through the stats routes. */
class MemberFetchBudgetsTest {

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
    private final MemberFetchBudgets budgets = new MemberFetchBudgets(properties, clock);
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void memberBudgetCapsPerMinuteThenSlides() {
        properties.setMemberPerMinute(2);
        FetchBudget first = budgets.forMember(a);
        assertThat(first.tryAcquire()).isTrue();
        assertThat(first.tryAcquire()).isTrue();
        assertThat(first.tryAcquire()).isFalse();
        assertThat(budgets.forMember(b).tryAcquire()).isTrue();
        advance(Duration.ofSeconds(61));
        assertThat(first.tryAcquire()).isTrue();
    }

    @Test
    void defaultsToTwentyPerMinute() {
        FetchBudget budget = budgets.forMember(a);
        for (int i = 0; i < 20; i++) {
            assertThat(budget.tryAcquire()).as("call %d", i + 1).isTrue();
        }
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void retryAfterIsTimeUntilOldestCallLeaves() {
        properties.setMemberPerMinute(1);
        FetchBudget budget = budgets.forMember(a);
        budget.tryAcquire();
        advance(Duration.ofSeconds(20));
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.retryAfter()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void idleMembersAreEvicted() {
        budgets.forMember(a).tryAcquire();
        assertThat(budgets.trackedMembers()).isEqualTo(1);
        advance(Duration.ofSeconds(61));
        budgets.evictIdle();
        assertThat(budgets.trackedMembers()).isZero();
    }
}
