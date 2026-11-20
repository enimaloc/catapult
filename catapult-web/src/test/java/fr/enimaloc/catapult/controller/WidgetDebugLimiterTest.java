package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.MutableClock;
import fr.enimaloc.catapult.controller.WidgetDebugLimiter.Admission;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetDebugLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T12:00:00Z"));
    private final WidgetDebugLimiter limiter = new WidgetDebugLimiter(clock, 3);
    private final UUID widget = UUID.randomUUID();

    /** {@code widget} relayed once, catapult-api recognizing it. */
    private void known(UUID widget) {
        assertThat(limiter.admit(widget)).isEqualTo(Admission.UNVERIFIED);
        limiter.relayed(widget, 204);
    }

    @Test
    void aKnownWidgetIsStillLimitedTo10Per10s() {
        known(widget);
        clock.advance(Duration.ofMillis(500));
        // Its first relay counts in its own allowance
        for (int i = 1; i < WidgetDebugLimiter.BATCHES; i++) {
            assertThat(limiter.admit(widget)).isEqualTo(Admission.KNOWN);
            clock.advance(Duration.ofMillis(500));
        }
        assertThat(limiter.admit(widget)).isEqualTo(Admission.REFUSED);

        // The first batch, sent at 12:00:00, leaves the window at 12:00:10
        clock.now = Instant.parse("2026-11-20T12:00:10Z");
        assertThat(limiter.admit(widget)).isEqualTo(Admission.KNOWN);
        assertThat(limiter.admit(widget)).isEqualTo(Admission.REFUSED);
    }

    @Test
    void randomTokensDoNotLockOutAKnownWidget() {
        known(widget);
        for (int i = 0; i < 10_000; i++) {
            UUID random = UUID.randomUUID();
            if (limiter.admit(random) != Admission.REFUSED) limiter.relayed(random, 404);
        }

        assertThat(limiter.admit(widget)).isEqualTo(Admission.KNOWN);
    }

    @Test
    void unknownTokensShareABoundedBudget() {
        for (int i = 0; i < WidgetDebugLimiter.UNVERIFIED_BATCHES; i++) {
            assertThat(limiter.admit(UUID.randomUUID())).isEqualTo(Admission.UNVERIFIED);
        }
        assertThat(limiter.admit(UUID.randomUUID())).isEqualTo(Admission.REFUSED);
        assertThat(limiter.admit(widget)).isEqualTo(Admission.REFUSED);

        clock.advance(WidgetDebugLimiter.WINDOW);
        assertThat(limiter.admit(widget)).isEqualTo(Admission.UNVERIFIED);
    }

    @Test
    void aWidgetIsKnownForAWhile_untilA404_andNotForAnUnreachableApi() {
        known(widget);
        clock.advance(WidgetDebugLimiter.KNOWN_FOR.minusSeconds(1));
        assertThat(limiter.admit(widget)).isEqualTo(Admission.KNOWN);
        // Each recognized relay keeps it known
        limiter.relayed(widget, 409);
        clock.advance(WidgetDebugLimiter.KNOWN_FOR.minusSeconds(1));
        assertThat(limiter.admit(widget)).isEqualTo(Admission.KNOWN);
        clock.advance(WidgetDebugLimiter.KNOWN_FOR);
        assertThat(limiter.admit(widget)).isEqualTo(Admission.UNVERIFIED);

        limiter.relayed(widget, 502);
        assertThat(limiter.admit(widget)).isEqualTo(Admission.UNVERIFIED);

        limiter.relayed(widget, 204);
        assertThat(limiter.admit(widget)).isEqualTo(Admission.KNOWN);
        limiter.relayed(widget, 404);
        assertThat(limiter.admit(widget)).isEqualTo(Admission.UNVERIFIED);
    }

    @Test
    void theKnownWidgetsAreBounded_theLeastRecentlySeenLeavingFirst() {
        UUID first = UUID.randomUUID();
        known(first);
        known(UUID.randomUUID());
        known(UUID.randomUUID());
        known(widget);

        assertThat(limiter.admit(widget)).isEqualTo(Admission.KNOWN);
        assertThat(limiter.admit(first)).isEqualTo(Admission.UNVERIFIED);
    }
}
