package fr.enimaloc.catapult.service.minecraft;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MinecraftGateServiceTest {

    @Mock private ExperimentService experimentService;
    @Mock private UserAccountRepository userAccountRepository;

    private final UserAccount user = new UserAccount();

    private MinecraftGateService gate(boolean enabled, String clientId, boolean inExperiment) {
        when(experimentService.evaluateGate(any(), eq(MinecraftGateService.EXPERIMENT_KEY))).thenReturn(inExperiment);
        when(experimentService.isRolledOut(any(), eq(MinecraftGateService.EXPERIMENT_KEY))).thenReturn(inExperiment);
        return new MinecraftGateService(experimentService, userAccountRepository, enabled, clientId);
    }

    @Test
    void isConfigured_requiresEnabledAndClientId() {
        assertThat(gate(true, "client-id", true).isConfigured()).isTrue();
        assertThat(gate(false, "client-id", true).isConfigured()).isFalse();
        assertThat(gate(true, "", true).isConfigured()).isFalse();
        assertThat(gate(true, "   ", true).isConfigured()).isFalse();
    }

    @Test
    void isAvailableFor_requiresConfigAndExperiment() {
        assertThat(gate(true, "client-id", true).isAvailableFor(user)).isTrue();
        assertThat(gate(true, "client-id", false).isAvailableFor(user)).isFalse();
        assertThat(gate(false, "client-id", true).isAvailableFor(user)).isFalse();
        assertThat(gate(true, "", true).isAvailableFor(user)).isFalse();
    }

    @Test
    void isAvailableForAccount_asksTheGateAboutTheAccount() {
        java.util.UUID id = java.util.UUID.randomUUID();
        when(userAccountRepository.findById(id)).thenReturn(java.util.Optional.of(user));

        assertThat(gate(true, "client-id", true).isAvailableForAccount(id)).isTrue();
        assertThat(gate(true, "client-id", false).isAvailableForAccount(id)).isFalse();
    }

    @Test
    void isAvailableForAccount_unknownAccountIsUnavailable() {
        MinecraftGateService gate = gate(true, "client-id", true);

        assertThat(gate.isAvailableForAccount(java.util.UUID.randomUUID())).isFalse();
        org.mockito.Mockito.verify(experimentService, org.mockito.Mockito.never()).evaluateGate(any(), any());
    }

    @Test
    void isAvailableReadOnly_usesIsRolledOut() {
        MinecraftGateService gate = gate(true, "client-id", true);

        assertThat(gate.isAvailableReadOnly(user)).isTrue();

        org.mockito.Mockito.verify(experimentService).isRolledOut(user, MinecraftGateService.EXPERIMENT_KEY);
        org.mockito.Mockito.verify(experimentService, org.mockito.Mockito.never()).evaluateGate(any(), any());
    }

    @Test
    void misconfigured_neverTouchesExperimentService() {
        MinecraftGateService gate = gate(true, "", true);

        assertThat(gate.isAvailableFor(user)).isFalse();
        assertThat(gate.isAvailableReadOnly(user)).isFalse();

        org.mockito.Mockito.verifyNoInteractions(experimentService);
    }
}
