package fr.enimaloc.catapult.service.degraded;

import fr.enimaloc.catapult.common.dto.DegradedEffectDto;
import fr.enimaloc.catapult.common.dto.DegradedStatusDto;
import fr.enimaloc.catapult.event.DegradedStateChangedEvent;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DegradedStateTest {
    private static final String PAGE = "https://s";
    private static final List<DegradedEffectDto> EFFECTS = List.of(
            new DegradedEffectDto("app.polling.interval-seconds", "60", "30"),
            new DegradedEffectDto("minecraft.friends-sync-interval-ms", "900000", "300000"));
    private static final DegradedStatusDto DEGRADED = new DegradedStatusDto(true, EFFECTS);
    private static final DegradedStatusDto HEALTHY = new DegradedStatusDto(false, null);

    private final ApiService api = mock(ApiService.class);
    private final InstatusClient instatus = mock(InstatusClient.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final MutableClock clock = new MutableClock();
    private DegradedState state;

    @BeforeEach
    void setUp() {
        state = new DegradedState(api, instatus, publisher, clock, PAGE, 120_000);
    }

    @Test
    void startsWithNone() {
        assertThat(state.current()).isEqualTo(DegradedView.NONE);
    }

    @Test
    void notDegraded_neverAsksInstatus() {
        when(api.degradedStatus()).thenReturn(HEALTHY);

        state.refresh();

        verifyNoInteractions(instatus);
        assertThat(state.current()).isEqualTo(DegradedView.NONE);
    }

    @Test
    void degraded_carriesTheEffects() {
        when(api.degradedStatus()).thenReturn(DEGRADED);
        when(instatus.latestIncidentUrl(PAGE)).thenReturn(Optional.empty());

        state.refresh();

        assertThat(state.current().effects()).isEqualTo(DEGRADED.effects());
    }

    @Test
    void degraded_withIncident_linksTheIncident() {
        when(api.degradedStatus()).thenReturn(DEGRADED);
        when(instatus.latestIncidentUrl(PAGE)).thenReturn(Optional.of("https://s/inc"));

        state.refresh();

        assertThat(state.current()).isEqualTo(new DegradedView(true, EFFECTS, "https://s/inc", true));
    }

    @Test
    void degraded_withoutIncident_linksTheStatusPage() {
        when(api.degradedStatus()).thenReturn(DEGRADED);
        when(instatus.latestIncidentUrl(PAGE)).thenReturn(Optional.empty());

        state.refresh();

        assertThat(state.current()).isEqualTo(new DegradedView(true, EFFECTS, PAGE, false));
    }

    @Test
    void degraded_withoutPageUrl_hasNoLink() {
        state = new DegradedState(api, instatus, publisher, clock, "", 120_000);
        when(api.degradedStatus()).thenReturn(DEGRADED);

        state.refresh();

        assertThat(state.current()).isEqualTo(new DegradedView(true, EFFECTS, null, false));
        verify(instatus, never()).latestIncidentUrl(anyString());
    }

    @Test
    void apiDown_keepsLastView() {
        when(api.degradedStatus()).thenReturn(DEGRADED).thenReturn(null);
        when(instatus.latestIncidentUrl(PAGE)).thenReturn(Optional.empty());
        state.refresh();
        DegradedView before = state.current();

        state.refresh();

        assertThat(state.current()).isEqualTo(before);
    }

    @Test
    void change_publishesOnce() {
        when(api.degradedStatus()).thenReturn(DEGRADED);
        when(instatus.latestIncidentUrl(PAGE)).thenReturn(Optional.empty());

        state.refresh();
        state.refresh();

        verify(publisher, times(1)).publishEvent(
                new DegradedStateChangedEvent(new DegradedView(true, EFFECTS, PAGE, false)));
        verify(publisher, times(1)).publishEvent(org.mockito.ArgumentMatchers.any(DegradedStateChangedEvent.class));
    }

    @Test
    void instatus_isAskedAtMostEveryInstatusRefresh() {
        when(api.degradedStatus()).thenReturn(DEGRADED);
        when(instatus.latestIncidentUrl(PAGE)).thenReturn(Optional.empty());

        state.refresh();
        clock.advance(Duration.ofSeconds(60));
        state.refresh();
        verify(instatus, times(1)).latestIncidentUrl(PAGE);

        clock.advance(Duration.ofSeconds(120));
        state.refresh();
        verify(instatus, times(2)).latestIncidentUrl(PAGE);
    }

    @Test
    void leavingDegraded_publishesNone() {
        when(api.degradedStatus()).thenReturn(DEGRADED).thenReturn(HEALTHY);
        when(instatus.latestIncidentUrl(PAGE)).thenReturn(Optional.of("https://s/inc"));
        state.refresh();

        state.refresh();

        assertThat(state.current()).isEqualTo(DegradedView.NONE);
        verify(publisher).publishEvent(new DegradedStateChangedEvent(DegradedView.NONE));
    }

    /** A clock the test moves forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-23T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
