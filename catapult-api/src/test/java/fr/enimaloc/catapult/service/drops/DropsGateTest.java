package fr.enimaloc.catapult.service.drops;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.experiment.DropsExperiment;
import fr.enimaloc.catapult.experiment.ExperimentSpec;
import fr.enimaloc.catapult.experiment.StreamTitleConditionsExperiment;
import fr.enimaloc.catapult.experiment.StreamTitlePreviewExperiment;
import fr.enimaloc.catapult.repository.title.StreamTitleStateRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The drops experiment's gate, and the stream title's conditions and preview ones. */
class DropsGateTest {

    private final ExperimentService experiments = mock(ExperimentService.class);
    private final DropsGate gate = new DropsGate(experiments);
    private final StreamTitleService title = new StreamTitleService(experiments, mock(StreamTitleStateRepository.class));
    private final UserAccount user = new UserAccount();

    @Test
    void theExperimentsDeclareTheGateKeys_tenPercentEnabled() {
        assertKey(DropsExperiment.class, DropsGate.EXPERIMENT_KEY, "drops");
        assertKey(StreamTitleConditionsExperiment.class, StreamTitleService.CONDITIONS_KEY, "stream.title.conditions");
        assertKey(StreamTitlePreviewExperiment.class, StreamTitleService.PREVIEW_KEY, "stream.title.preview");
    }

    private static void assertKey(Class<?> experiment, String gateKey, String key) {
        ExperimentSpec spec = experiment.getAnnotation(ExperimentSpec.class);
        assertThat(spec.key()).isEqualTo(gateKey).isEqualTo(key);
        assertThat(spec.variants()).extracting(ExperimentSpec.Variant::key).containsExactly("control", "enabled");
        assertThat(spec.variants()).extracting(ExperimentSpec.Variant::weight).containsExactly(90, 10);
    }

    @Test
    void isAvailableFor_evaluatesTheGate() {
        when(experiments.evaluateGate(user, DropsGate.EXPERIMENT_KEY)).thenReturn(true);
        when(experiments.evaluateGate(user, StreamTitleService.PREVIEW_KEY)).thenReturn(true);

        assertThat(gate.isAvailableFor(user)).isTrue();
        assertThat(title.previewAvailableFor(user)).isTrue();
    }

    @Test
    void theBackgroundPaths_onlyReadTheAssignment() {
        when(experiments.isRolledOut(user, DropsGate.EXPERIMENT_KEY)).thenReturn(true);
        when(experiments.isRolledOut(user, StreamTitleService.CONDITIONS_KEY)).thenReturn(true);

        assertThat(gate.isAvailableReadOnly(user)).isTrue();
        assertThat(title.conditionsAvailableReadOnly(user)).isTrue();
        verify(experiments, never()).evaluateGate(any(), anyString());
    }
}
