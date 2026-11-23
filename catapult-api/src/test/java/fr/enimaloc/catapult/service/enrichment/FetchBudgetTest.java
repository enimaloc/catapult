package fr.enimaloc.catapult.service.enrichment;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FetchBudgetTest {

    @Test
    void onceChargesOnlyTheFirstCall() {
        AtomicInteger asked = new AtomicInteger();
        FetchBudget once = FetchBudget.once(() -> { asked.incrementAndGet(); return true; });
        assertThat(once.tryAcquire()).isTrue();
        assertThat(once.tryAcquire()).isTrue();
        assertThat(asked).hasValue(1);
    }

    @Test
    void onceStaysRefused() {
        AtomicInteger asked = new AtomicInteger();
        FetchBudget once = FetchBudget.once(() -> { asked.incrementAndGet(); return false; });
        assertThat(once.tryAcquire()).isFalse();
        assertThat(once.tryAcquire()).isFalse();
        assertThat(asked).hasValue(1);
    }
}
