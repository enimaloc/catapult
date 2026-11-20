package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetDebugLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T12:00:00Z"));
    private final WidgetDebugLimiter limiter = new WidgetDebugLimiter(clock, 3);
    private final UUID widget = UUID.randomUUID();

    @Test
    void aWidgetGetsItsBatchesPerWindow_thenWaitsForTheOldestToLeaveIt() {
        for (int i = 0; i < WidgetDebugLimiter.BATCHES; i++) {
            assertThat(limiter.tryAcquire(widget)).isTrue();
            clock.advance(Duration.ofMillis(500));
        }
        assertThat(limiter.tryAcquire(widget)).isFalse();

        // The first batch, sent at 12:00:00, leaves the window at 12:00:10
        clock.now = Instant.parse("2026-11-20T12:00:10Z");
        assertThat(limiter.tryAcquire(widget)).isTrue();
        assertThat(limiter.tryAcquire(widget)).isFalse();
    }

    @Test
    void theWidgetsTrackedAreBounded_quietOnesMakingRoom() {
        assertThat(limiter.tryAcquire(UUID.randomUUID())).isTrue();
        assertThat(limiter.tryAcquire(UUID.randomUUID())).isTrue();
        assertThat(limiter.tryAcquire(widget)).isTrue();

        // Three widgets sent within the window: a fourth is refused until one of them goes quiet
        UUID fourth = UUID.randomUUID();
        assertThat(limiter.tryAcquire(fourth)).isFalse();
        // A widget already tracked still gets its allowance
        assertThat(limiter.tryAcquire(widget)).isTrue();

        clock.advance(WidgetDebugLimiter.WINDOW.plusMillis(1));
        assertThat(limiter.tryAcquire(fourth)).isTrue();
    }
}
