package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.experiment.ExperimentSpec;
import fr.enimaloc.catapult.experiment.RaidRestoreGameExperiment;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RaidRestoreGateServiceTest {

    private final ExperimentService experiments = mock(ExperimentService.class);
    private final RaidRestoreGateService gate = new RaidRestoreGateService(experiments);
    private final UserAccount user = new UserAccount();

    @Test
    void theExperimentDeclaresTheGateKey() {
        ExperimentSpec spec = RaidRestoreGameExperiment.class.getAnnotation(ExperimentSpec.class);

        assertThat(spec.key()).isEqualTo(RaidRestoreGateService.EXPERIMENT_KEY).isEqualTo("raid.restore-game");
        assertThat(spec.variants()).extracting(ExperimentSpec.Variant::key).containsExactly("control", "enabled");
    }

    @Test
    void isAvailableFor_evaluatesTheGate() {
        when(experiments.evaluateGate(user, RaidRestoreGateService.EXPERIMENT_KEY)).thenReturn(true);

        assertThat(gate.isAvailableFor(user)).isTrue();
    }

    @Test
    void isAvailableReadOnly_onlyReadsTheAssignment() {
        when(experiments.isRolledOut(user, RaidRestoreGateService.EXPERIMENT_KEY)).thenReturn(true);

        assertThat(gate.isAvailableReadOnly(user)).isTrue();
        verify(experiments, never()).evaluateGate(user, RaidRestoreGateService.EXPERIMENT_KEY);
    }
}
