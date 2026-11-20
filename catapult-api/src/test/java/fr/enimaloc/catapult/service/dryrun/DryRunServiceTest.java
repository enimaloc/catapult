package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.dryrun.MemberDryRun;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.dryrun.MemberDryRunRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DryRunServiceTest {

    static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    final MemberDryRunRepository dryRuns = mock(MemberDryRunRepository.class);
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final ActivityLogService activityLog = mock(ActivityLogService.class);
    final List<Object> events = new java.util.ArrayList<>();
    final DryRunRegistry registry = new DryRunRegistry(dryRuns, events::add);
    final UUID member = UUID.randomUUID();
    final UUID admin = UUID.randomUUID();
    final UserAccount account = new UserAccount();
    DryRunService service;

    @BeforeEach
    void setUp() {
        account.setId(member);
        when(users.findById(member)).thenReturn(Optional.of(account));
        when(dryRuns.save(any(MemberDryRun.class))).thenAnswer(i -> i.getArgument(0));
        service = new DryRunService(dryRuns, users, registry, activityLog, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MemberDryRun stored() {
        MemberDryRun row = new MemberDryRun();
        row.setUserId(member);
        row.setEnabledBy(admin);
        row.setEnabledAt(NOW.minusSeconds(3600));
        when(dryRuns.findById(member)).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void enableRecordsWhoAndWhen() {
        MemberDryRun row = service.enable(admin, member);

        ArgumentCaptor<MemberDryRun> saved = ArgumentCaptor.forClass(MemberDryRun.class);
        verify(dryRuns).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(member);
        assertThat(saved.getValue().getEnabledBy()).isEqualTo(admin);
        assertThat(saved.getValue().getEnabledAt()).isEqualTo(NOW);
        assertThat(row).isSameAs(saved.getValue());
        verify(activityLog).addEntry(member, "INFO", "Dry run turned on by an administrator");
    }

    @Test
    void enable_isAConflictWhenAlreadyOn() {
        stored();

        assertThatThrownBy(() -> service.enable(admin, member)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(dryRuns, never()).save(any());
    }

    @Test
    void enable_andDisable_ofAnUnknownMemberAre404() {
        UUID unknown = UUID.randomUUID();
        when(users.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enable(admin, unknown)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.disable(admin, unknown)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(dryRuns, never()).save(any());
    }

    @Test
    void disableForgetsTheRememberedGame() {
        MemberDryRun row = stored();
        registry.activate(member);

        service.disable(member, member);

        verify(dryRuns).delete(row);
        assertThat(events).containsExactly(new DryRunEndedEvent(member));
        verify(activityLog).addEntry(member, "INFO", "Dry run turned off");
    }

    @Test
    void disableLogsEvenIfForgettingFails() {
        stored();
        DryRunRegistry failing = new DryRunRegistry(dryRuns, e -> {
            throw new IllegalStateException("boom");
        });
        failing.activate(member);
        service = new DryRunService(dryRuns, users, failing, activityLog, Clock.fixed(NOW, ZoneOffset.UTC));

        service.disable(member, member);

        verify(activityLog).addEntry(member, "INFO", "Dry run turned off");
        assertThat(failing.isActive(member)).isFalse();
    }

    @Test
    void disable_whenNotInDryRun_changesNothing() {
        when(dryRuns.findById(member)).thenReturn(Optional.empty());

        service.disable(admin, member);

        verify(dryRuns, never()).delete(any());
        assertThat(events).isEmpty();
        verify(activityLog, never()).addEntry(any(), anyString(), anyString());
    }

    @Test
    void registryReflectsChangesAtOnce() {
        service.enable(admin, member);
        assertThat(registry.isActive(member)).isTrue();

        stored();
        service.disable(admin, member);
        assertThat(registry.isActive(member)).isFalse();
    }

    @Test
    void state_isTheStoredRow() {
        MemberDryRun row = stored();

        assertThat(service.state(member)).contains(row);
    }

    @Test
    void activeAmong_asksOnceForTheWholeSet_andNotAtAllForNone() {
        UUID other = UUID.randomUUID();
        MemberDryRun row = new MemberDryRun();
        row.setUserId(member);
        when(dryRuns.findAllById(List.of(member, other))).thenReturn(List.of(row));

        assertThat(service.activeAmong(List.of(member, other))).isEqualTo(Set.of(member));
        assertThat(service.activeAmong(List.of())).isEmpty();
        verify(dryRuns).findAllById(any());
    }
}
