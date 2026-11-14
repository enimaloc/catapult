package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnkamaGateServiceTest {

    @Mock private ExperimentService experimentService;
    @Mock private UserAccountRepository userAccountRepository;

    private final UserAccount user = new UserAccount();

    private AnkamaGateService gate(boolean enabled, boolean inExperiment) {
        when(experimentService.evaluateGate(any(), eq(AnkamaGateService.EXPERIMENT_KEY))).thenReturn(inExperiment);
        when(experimentService.isRolledOut(any(), eq(AnkamaGateService.EXPERIMENT_KEY))).thenReturn(inExperiment);
        return new AnkamaGateService(experimentService, userAccountRepository, enabled);
    }

    @Test
    void isConfigured_followsTheEnabledFlag() {
        assertThat(gate(true, true).isConfigured()).isTrue();
        assertThat(gate(false, true).isConfigured()).isFalse();
    }

    @Test
    void isAvailableFor_requiresConfigAndExperiment() {
        assertThat(gate(true, true).isAvailableFor(user)).isTrue();
        assertThat(gate(true, false).isAvailableFor(user)).isFalse();
        assertThat(gate(false, true).isAvailableFor(user)).isFalse();
    }

    @Test
    void isAvailableForAccount_asksTheGateAboutTheAccount() {
        UUID id = UUID.randomUUID();
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        assertThat(gate(true, true).isAvailableForAccount(id)).isTrue();
        assertThat(gate(true, false).isAvailableForAccount(id)).isFalse();
    }

    @Test
    void isAvailableForAccount_unknownAccountIsUnavailable() {
        AnkamaGateService gate = gate(true, true);

        assertThat(gate.isAvailableForAccount(UUID.randomUUID())).isFalse();
        verify(experimentService, never()).evaluateGate(any(), any());
    }

    @Test
    void isAvailableReadOnly_usesIsRolledOut() {
        AnkamaGateService gate = gate(true, true);

        assertThat(gate.isAvailableReadOnly(user)).isTrue();

        verify(experimentService).isRolledOut(user, AnkamaGateService.EXPERIMENT_KEY);
        verify(experimentService, never()).evaluateGate(any(), any());
    }

    @Test
    void disabled_neverTouchesExperimentService() {
        AnkamaGateService gate = new AnkamaGateService(experimentService, userAccountRepository, false);

        assertThat(gate.isAvailableFor(user)).isFalse();
        assertThat(gate.isAvailableReadOnly(user)).isFalse();

        verifyNoInteractions(experimentService);
    }
}
