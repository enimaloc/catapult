package fr.enimaloc.catapult.service.epic;

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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EpicGateServiceTest {

    @Mock private ExperimentService experimentService;
    @Mock private UserAccountRepository userAccountRepository;

    private final UserAccount user = new UserAccount();

    private EpicGateService gate(boolean enabled, String clientId, String secret, boolean inExperiment) {
        when(experimentService.evaluateGate(any(), eq(EpicGateService.EXPERIMENT_KEY))).thenReturn(inExperiment);
        when(experimentService.isRolledOut(any(), eq(EpicGateService.EXPERIMENT_KEY))).thenReturn(inExperiment);
        return new EpicGateService(experimentService, userAccountRepository, enabled, clientId, secret);
    }

    @Test
    void isConfigured_needsTheFlagAndBothCredentials() {
        assertThat(gate(true, "id", "secret", true).isConfigured()).isTrue();
        assertThat(gate(false, "id", "secret", true).isConfigured()).isFalse();
        assertThat(gate(true, "", "secret", true).isConfigured()).isFalse();
        assertThat(gate(true, "id", " ", true).isConfigured()).isFalse();
    }

    @Test
    void isAvailableFor_requiresConfigAndExperiment() {
        assertThat(gate(true, "id", "secret", true).isAvailableFor(user)).isTrue();
        assertThat(gate(true, "id", "secret", false).isAvailableFor(user)).isFalse();
        assertThat(gate(false, "id", "secret", true).isAvailableFor(user)).isFalse();
    }

    @Test
    void isAvailableForAccount_asksTheGateAboutTheAccount() {
        UUID id = UUID.randomUUID();
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        assertThat(gate(true, "id", "secret", true).isAvailableForAccount(id)).isTrue();
        assertThat(gate(true, "id", "secret", false).isAvailableForAccount(id)).isFalse();
        assertThat(gate(true, "id", "secret", true).isAvailableForAccount(UUID.randomUUID())).isFalse();
    }

    @Test
    void isAvailableReadOnly_neverAssigns() {
        assertThat(gate(true, "id", "secret", true).isAvailableReadOnly(user)).isTrue();
        assertThat(gate(false, "id", "secret", true).isAvailableReadOnly(user)).isFalse();
        verify(experimentService, never()).evaluateGate(any(), any());
    }
}
