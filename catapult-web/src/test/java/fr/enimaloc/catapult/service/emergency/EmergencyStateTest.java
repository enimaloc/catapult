package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.common.dto.emergency.EmergencyStatusDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStopDto;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmergencyStateTest {
    private static final EmergencyStatusDto STOPPED = new EmergencyStatusDto(List.of(
            new EmergencyStopDto(UUID.randomUUID(), "XBOX", "GLOBAL", "THRESHOLD", null, Instant.EPOCH, null, null, null)));

    private final ApiService api = mock(ApiService.class);
    private final MutableClock clock = new MutableClock();
    private final EmergencyState state = new EmergencyState(api, clock);

    @Test
    void readsTheStopsOnceFor30Seconds() {
        when(api.emergencyStatus()).thenReturn(STOPPED);

        assertThat(state.globalStops()).hasSize(1);
        clock.advance(Duration.ofSeconds(29));
        assertThat(state.globalStops()).hasSize(1);

        verify(api, times(1)).emergencyStatus();
    }

    @Test
    void asksAgainOnceTheCacheIsOld() {
        when(api.emergencyStatus()).thenReturn(STOPPED, new EmergencyStatusDto(List.of()));

        assertThat(state.globalStops()).hasSize(1);
        clock.advance(Duration.ofSeconds(31));

        assertThat(state.globalStops()).isEmpty();
    }

    @Test
    void noBannerWhenTheApiIsDown_neverAnError() {
        when(api.emergencyStatus()).thenReturn(null);
        assertThat(state.globalStops()).isEmpty();

        when(api.emergencyStatus()).thenThrow(new IllegalStateException("down"));
        clock.advance(Duration.ofSeconds(31));
        assertThat(state.globalStops()).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-19T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
