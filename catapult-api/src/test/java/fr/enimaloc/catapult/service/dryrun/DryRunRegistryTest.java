package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.dryrun.MemberDryRun;
import fr.enimaloc.catapult.repository.dryrun.MemberDryRunRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DryRunRegistryTest {

    final MemberDryRunRepository dryRuns = mock(MemberDryRunRepository.class);
    final List<Object> events = new java.util.ArrayList<>();
    final DryRunRegistry registry = new DryRunRegistry(dryRuns, events::add);
    final UUID member = UUID.randomUUID();
    final UUID other = UUID.randomUUID();

    private static MemberDryRun row(UUID userId) {
        MemberDryRun row = new MemberDryRun();
        row.setUserId(userId);
        return row;
    }

    @Test
    void nobodyIsInDryRunBeforeTheFirstLoad() {
        assertThat(registry.isActive(member)).isFalse();
        assertThat(registry.isActive(null)).isFalse();
        assertThat(registry.members()).isEmpty();
    }

    @Test
    void registryReflectsChangesAtOnce() {
        registry.activate(member);
        assertThat(registry.isActive(member)).isTrue();
        assertThat(registry.members()).containsExactly(member);

        registry.deactivate(member);
        assertThat(registry.isActive(member)).isFalse();
    }

    @Test
    void loadMirrorsTheDatabase_whatAnotherInstanceChangedIncluded() {
        registry.activate(member);
        when(dryRuns.findAll()).thenReturn(List.of(row(other)));

        registry.load();

        assertThat(registry.isActive(member)).isFalse();
        assertThat(registry.isActive(other)).isTrue();
    }

    @Test
    void refreshReloadsFromTheDatabase() {
        when(dryRuns.findAll()).thenReturn(List.of(row(member)));

        registry.refresh();

        assertThat(registry.isActive(member)).isTrue();
    }

    @Test
    void aMemberLeavingTheSetIsForgottenOnReload() {
        registry.activate(member);
        registry.activate(other);
        when(dryRuns.findAll()).thenReturn(List.of(row(other)));

        registry.load();

        assertThat(events).containsExactly(new DryRunEndedEvent(member));
    }

    @Test
    void aMemberLeavingTheSetHereIsForgottenAtOnce_andOnlyOnce() {
        registry.activate(member);

        registry.deactivate(member);
        registry.deactivate(member);
        when(dryRuns.findAll()).thenReturn(List.of());
        registry.load();

        assertThat(events).containsExactly(new DryRunEndedEvent(member));
    }

    @Test
    void aFailingListenerNeitherBreaksTheRegistryNorTheOtherMembers() {
        List<Object> seen = new java.util.ArrayList<>();
        DryRunRegistry failing = new DryRunRegistry(dryRuns, e -> {
            seen.add(e);
            throw new IllegalStateException("boom");
        });
        failing.activate(member);
        failing.activate(other);
        when(dryRuns.findAll()).thenReturn(List.of());

        failing.load();

        assertThat(failing.members()).isEmpty();
        assertThat(seen).hasSize(2);
    }
}
