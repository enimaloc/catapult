package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.MemberDebugSession;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import fr.enimaloc.catapult.repository.debug.MemberDebugSessionRepository;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberDebugServiceTest {

    static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    MemberDebugSessionRepository sessions = mock(MemberDebugSessionRepository.class);
    DebugExchangeRepository exchanges = mock(DebugExchangeRepository.class);
    DebugMemberRegistry registry = new DebugMemberRegistry(sessions);
    MemberDebugService service;
    final UUID member = UUID.randomUUID();
    final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MemberDebugService(sessions, exchanges, registry, Clock.fixed(NOW, ZoneOffset.UTC));
        when(sessions.save(any(MemberDebugSession.class))).thenAnswer(i -> i.getArgument(0));
    }

    private MemberDebugSession stored(boolean active, Instant disabledAt) {
        MemberDebugSession s = new MemberDebugSession();
        s.setUserId(member);
        s.setActive(active);
        s.setDisabledAt(disabledAt);
        s.setEnabledAt(NOW.minusSeconds(3600));
        when(sessions.findById(member)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void enable_makesTheMemberDebugged() {
        when(sessions.findById(member)).thenReturn(Optional.empty());

        MemberDebugSession s = service.enable(member, true, admin);

        assertThat(registry.modeOf(member)).contains(new DebugMode(member, true));
        assertThat(s.isActive()).isTrue();
        assertThat(s.isRevealSecrets()).isTrue();
        assertThat(s.getEnabledBy()).isEqualTo(admin);
        assertThat(s.getEnabledAt()).isEqualTo(NOW);
        assertThat(s.getDisabledAt()).isNull();
    }

    @Test
    void enable_twice_conflicts() {
        stored(true, null);
        registry.activate(member, false);

        assertThatThrownBy(() -> service.enable(member, false, admin))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void disable_keepsExchanges_andStampsDisabledAt() {
        MemberDebugSession s = stored(true, null);
        registry.activate(member, false);

        service.disable(member);

        assertThat(s.isActive()).isFalse();
        assertThat(s.getDisabledAt()).isEqualTo(NOW);
        assertThat(registry.modeOf(member)).isEmpty();
        verifyNoInteractions(exchanges);
    }

    @Test
    void reEnable_keepsExchanges_andClearsDisabledAt() {
        MemberDebugSession s = stored(false, NOW.minusSeconds(60));

        service.enable(member, false, admin);

        assertThat(s.isActive()).isTrue();
        assertThat(s.getDisabledAt()).isNull();
        assertThat(registry.modeOf(member)).contains(new DebugMode(member, false));
        verifyNoInteractions(exchanges);
    }

    @Test
    void clear_deletesAllExchangesOfTheMember_only() {
        MemberDebugSession s = stored(true, null);

        service.clear(member);

        verify(exchanges).deleteByUserId(member);
        verifyNoMoreInteractions(exchanges);
        assertThat(s.isActive()).isTrue();
        verify(sessions, never()).delete(any());
    }

    @Test
    void registry_loadsActiveSessionsAtStartup() {
        MemberDebugSession on = new MemberDebugSession();
        on.setUserId(member);
        on.setActive(true);
        on.setRevealSecrets(true);
        when(sessions.findByActiveTrue()).thenReturn(List.of(on));

        registry.load();

        assertThat(registry.modeOf(member)).contains(new DebugMode(member, true));
        assertThat(registry.modeOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    void activeStates_indexesTheActiveSessionsByUser() {
        MemberDebugSession on = new MemberDebugSession();
        on.setUserId(member);
        when(sessions.findByUserIdInAndActiveTrue(List.of(member))).thenReturn(List.of(on));

        assertThat(service.activeStates(List.of(member))).isEqualTo(Map.of(member, on));
        assertThat(service.activeStates(List.of())).isEmpty();
    }

    @Test
    void enable_inTransaction_updatesTheRegistryOnlyAfterCommit() {
        when(sessions.findById(member)).thenReturn(Optional.empty());
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.enable(member, false, admin);
            assertThat(registry.modeOf(member)).isEmpty();

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(registry.modeOf(member)).contains(new DebugMode(member, false));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void disable_inTransaction_rolledBack_keepsTheMemberDebugged() {
        stored(true, null);
        registry.activate(member, false);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.disable(member);
            // rollback: afterCommit is never called
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(registry.modeOf(member)).contains(new DebugMode(member, false));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
