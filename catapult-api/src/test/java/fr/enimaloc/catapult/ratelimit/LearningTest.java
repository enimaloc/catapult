package fr.enimaloc.catapult.ratelimit;

import fr.enimaloc.catapult.ratelimit.LimitEstimator.Estimate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The limit and the resume delay learned from 429s. */
class LearningTest {

    private static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");

    private static LimitEstimator calls(int n, Duration spread) {
        LimitEstimator e = new LimitEstimator(Duration.ofHours(1));
        for (int i = 0; i < n; i++) e.record(T0.plusMillis(spread.toMillis() * i / n));
        return e;
    }

    @Test
    void a429_limitsToTheCallsBeforeTheRefusedOne_inTheWindowOfTheWait() {
        LimitEstimator e = calls(30, Duration.ofSeconds(50));

        e.on429(T0.plusSeconds(50), Duration.ofSeconds(60));

        assertThat(e.estimate()).contains(new Estimate(29, Duration.ofMinutes(1)));
    }

    @Test
    void a429_neverLoosensTheLimit_butAnotherWindowReplacesIt() {
        LimitEstimator e = calls(30, Duration.ofSeconds(50));
        e.on429(T0.plusSeconds(50), Duration.ofSeconds(60));
        for (int i = 0; i < 20; i++) e.record(T0.plusSeconds(51));

        e.on429(T0.plusSeconds(52), Duration.ofSeconds(30));
        assertThat(e.estimate()).contains(new Estimate(29, Duration.ofMinutes(1)));

        e.on429(T0.plusSeconds(52), Duration.ofSeconds(5));
        assertThat(e.estimate()).contains(new Estimate(23, Duration.ofSeconds(10)));

        e.on429(T0.plusSeconds(52), Duration.ofHours(3));
        assertThat(e.estimate()).contains(new Estimate(49, Duration.ofHours(1)));
    }

    @Test
    void oneFewCalls429_atMostHalvesTheLimit() {
        LimitEstimator e = calls(30, Duration.ofSeconds(50));
        e.on429(T0.plusSeconds(50), Duration.ofSeconds(60));
        Instant later = T0.plus(Duration.ofMinutes(10));
        e.record(later);

        e.on429(later, Duration.ofSeconds(60));
        assertThat(e.estimate()).contains(new Estimate(15, Duration.ofMinutes(1)));
    }

    @Test
    void theLimit_isNeverUnderOneCall() {
        LimitEstimator e = new LimitEstimator(Duration.ofHours(1));
        e.on429(T0, Duration.ofSeconds(1));
        assertThat(e.estimate()).contains(new Estimate(1, Duration.ofSeconds(10)));
        assertThat(e.oldestWithin(Duration.ofSeconds(10), T0)).isEmpty();
    }

    @Test
    void longNearTheLimitWithoutA429_raisesItByATenth() {
        LimitEstimator e = calls(30, Duration.ofSeconds(50));
        e.on429(T0.plusSeconds(50), Duration.ofSeconds(60));
        Instant later = T0.plus(Duration.ofHours(2));

        e.maybeRaise(later);
        assertThat(e.estimate()).contains(new Estimate(29, Duration.ofMinutes(1)));

        for (int i = 24; i >= 0; i--) e.record(later.minusSeconds(i));
        e.maybeRaise(later);
        assertThat(e.estimate()).contains(new Estimate(31, Duration.ofMinutes(1)));

        e.maybeRaise(later.plusSeconds(1));
        assertThat(e.estimate()).contains(new Estimate(31, Duration.ofMinutes(1)));
        assertThat(e.oldestWithin(Duration.ofMinutes(1), later)).contains(later.minusSeconds(24));

        e.reset();
        e.maybeRaise(later);
        assertThat(e.estimate()).isEmpty();
        e.set(new Estimate(5, Duration.ofSeconds(10)), null);
        assertThat(e.estimate()).contains(new Estimate(5, Duration.ofSeconds(10)));
    }

    @Test
    void theResumeDelay_convergesWithoutGoingUnderWhatFailed() {
        ResumeDelay d = new ResumeDelay(Duration.ofHours(24));
        assertThat(d.current()).isEqualTo(Duration.ofSeconds(60));

        d.onSuccess(T0);
        assertThat(d.current()).isEqualTo(Duration.ofSeconds(48));
        d.onFailure(T0);
        assertThat(d.current()).isEqualTo(Duration.ofSeconds(96));
        d.onSuccess(T0);
        assertThat(d.current()).isEqualTo(Duration.ofMillis(76_800));
        for (int i = 0; i < 10; i++) d.onSuccess(T0);
        assertThat(d.current()).isEqualTo(Duration.ofSeconds(48));
        assertThat(d.state().ok()).isEqualTo(Duration.ofSeconds(48));
        assertThat(d.state().fail()).isEqualTo(Duration.ofSeconds(48));
    }

    @Test
    void theResumeDelay_forgetsAnOldFailure_isCapped_andResets() {
        ResumeDelay d = new ResumeDelay(Duration.ofHours(24));
        for (int i = 0; i < 6; i++) d.onFailure(T0);
        assertThat(d.current()).isEqualTo(ResumeDelay.MAX);

        d.onSuccess(T0.plus(Duration.ofDays(2)));
        assertThat(d.state().fail()).isNull();
        assertThat(d.current()).isEqualTo(Duration.ofMinutes(8));

        d.restore(new ResumeDelay.State(Duration.ofMillis(1100), null, null, null));
        d.onSuccess(T0);
        assertThat(d.current()).isEqualTo(Duration.ofSeconds(1));
        d.restore(new ResumeDelay.State(null, null, null, null));
        assertThat(d.current()).isEqualTo(ResumeDelay.INITIAL);
        d.onFailure(T0);
        d.reset();
        assertThat(d.state()).isEqualTo(new ResumeDelay.State(ResumeDelay.INITIAL, null, null, null));
    }
}
