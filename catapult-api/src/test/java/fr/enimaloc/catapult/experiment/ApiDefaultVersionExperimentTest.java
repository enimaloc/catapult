package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default API version experiment as it ships: ACTIVE from boot, everyone in {@code v2} (the control, weight 100),
 * {@code v3} at weight 0 until an admin swaps the weights.
 */
class ApiDefaultVersionExperimentTest {

    private final ExperimentSpec spec = ApiDefaultVersionExperiment.class.getAnnotation(ExperimentSpec.class);

    @Test
    void isActiveFromBoot_underItsKey() {
        assertThat(spec.key()).isEqualTo(ApiDefaultVersionExperiment.KEY).isEqualTo("api.default-version");
        assertThat(spec.initialStatus()).isEqualTo(Experiment.Status.ACTIVE);
    }

    @Test
    void putsEveryoneInV2_andNoOneInV3() {
        assertThat(spec.variants()).hasSize(2);
        ExperimentSpec.Variant v2 = spec.variants()[0];
        ExperimentSpec.Variant v3 = spec.variants()[1];

        assertThat(v2.key()).isEqualTo(ApiDefaultVersionExperiment.V2_VARIANT).isEqualTo("v2");
        assertThat(v2.weight()).isEqualTo(100);
        assertThat(v2.control()).isTrue();
        assertThat(v3.key()).isEqualTo(ApiDefaultVersionExperiment.V3_VARIANT).isEqualTo("v3");
        assertThat(v3.weight()).isZero();
        assertThat(v3.control()).isFalse();
    }

    @Test
    void assignsEveryAccount_atRandom() {
        assertThat(spec.rules()).hasSize(1);
        assertThat(spec.rules()[0].type()).isEqualTo(RuleType.RANDOM);
        assertThat(spec.rules()[0].percent()).isEqualTo(100);
        assertThat(spec.overrides()).isEmpty();
    }
}
