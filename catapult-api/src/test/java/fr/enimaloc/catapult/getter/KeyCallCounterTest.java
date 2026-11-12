package fr.enimaloc.catapult.getter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class KeyCallCounterTest {

    private static final Instant T0 = Instant.parse("2026-11-12T23:59:50Z");

    @Test
    void callsAreCountedInTheWindow_andInTheirDay() {
        KeyCallCounter counter = new KeyCallCounter(Duration.ofSeconds(5), at -> LocalDate.ofInstant(at, ZoneOffset.UTC));
        counter.record("k", T0);
        counter.record("k", T0.plusSeconds(3));

        assertThat(counter.inWindow("k", T0.plusSeconds(4))).isEqualTo(2);
        assertThat(counter.inWindow("k", T0.plusSeconds(6))).isEqualTo(1);
        assertThat(counter.inPeriod("k", T0.plusSeconds(6))).isEqualTo(2);
        assertThat(counter.inWindow("other", T0)).isZero();
        assertThat(counter.inPeriod("other", T0)).isZero();

        // A new day counts afresh
        assertThat(counter.inPeriod("k", T0.plusSeconds(20))).isZero();
        counter.record("k", T0.plusSeconds(20));
        assertThat(counter.inPeriod("k", T0.plusSeconds(21))).isEqualTo(1);
    }
}
