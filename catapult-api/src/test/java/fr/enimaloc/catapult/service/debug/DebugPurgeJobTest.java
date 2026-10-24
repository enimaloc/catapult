package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.MemberDebugSession;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import fr.enimaloc.catapult.repository.debug.MemberDebugSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DebugPurgeJobTest {

    static final Instant NOW = Instant.parse("2026-10-24T04:30:00Z");

    MemberDebugSessionRepository sessions = mock(MemberDebugSessionRepository.class);
    DebugExchangeRepository exchanges = mock(DebugExchangeRepository.class);
    DebugPurgeJob job = new DebugPurgeJob(sessions, exchanges, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void purge_deletesOnlySessionsDisabledMoreThan30DaysAgo() {
        MemberDebugSession old = new MemberDebugSession();
        old.setUserId(UUID.randomUUID());
        old.setDisabledAt(NOW.minus(31, ChronoUnit.DAYS));
        // the repository applies the cutoff: it must be exactly now - 30 days, and look at inactive sessions only
        when(sessions.findByActiveFalseAndDisabledAtBefore(NOW.minus(30, ChronoUnit.DAYS))).thenReturn(List.of(old));

        job.run();

        verify(exchanges).deleteByUserIdIn(List.of(old.getUserId()));
        verify(sessions).deleteAll(List.of(old));
    }

    @Test
    void purge_keepsEverythingWhenNothingIsOldEnough() {
        // a 29-day-old or active session is not returned by the cutoff query
        when(sessions.findByActiveFalseAndDisabledAtBefore(any())).thenReturn(List.of());

        job.run();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(sessions).findByActiveFalseAndDisabledAtBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(NOW.minus(30, ChronoUnit.DAYS));
        verifyNoInteractions(exchanges);
        verify(sessions, never()).deleteAll(any());
    }
}
