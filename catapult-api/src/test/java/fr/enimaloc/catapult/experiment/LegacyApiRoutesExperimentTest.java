package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The legacy routes experiment as it ships: ACTIVE from boot, everyone in {@code control} (the legacy routes cut),
 * the {@code legacy} variant reached by an admin override only.
 */
class LegacyApiRoutesExperimentTest {

    private final ExperimentSpec spec = LegacyApiRoutesExperiment.class.getAnnotation(ExperimentSpec.class);

    @Test
    void isActiveFromBoot_underItsKey() {
        assertThat(spec.key()).isEqualTo(LegacyApiRoutesExperiment.KEY).isEqualTo("api.legacy-routes");
        assertThat(spec.initialStatus()).isEqualTo(Experiment.Status.ACTIVE);
    }

    @Test
    void putsEveryoneInControl_andNoOneInLegacy() {
        assertThat(spec.variants()).hasSize(2);
        ExperimentSpec.Variant control = spec.variants()[0];
        ExperimentSpec.Variant legacy = spec.variants()[1];

        assertThat(control.key()).isEqualTo("control");
        assertThat(control.weight()).isEqualTo(100);
        assertThat(control.control()).isTrue();
        assertThat(legacy.key()).isEqualTo(LegacyApiRoutesExperiment.LEGACY_VARIANT).isEqualTo("legacy");
        assertThat(legacy.weight()).isZero();
        assertThat(legacy.control()).isFalse();
    }

    @Test
    void assignsEveryAccount_atRandom() {
        assertThat(spec.rules()).hasSize(1);
        assertThat(spec.rules()[0].type()).isEqualTo(RuleType.RANDOM);
        assertThat(spec.rules()[0].percent()).isEqualTo(100);
        assertThat(spec.overrides()).isEmpty();
    }
}
