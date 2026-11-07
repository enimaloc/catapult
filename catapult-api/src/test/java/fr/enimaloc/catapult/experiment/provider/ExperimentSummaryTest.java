package fr.enimaloc.catapult.experiment.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentSummaryTest {

    @Test
    void recordAccessors_returnConstructorValues() {
        var summary = new ExperimentSummary("exp-key", "My Experiment");

        assertThat(summary.key()).isEqualTo("exp-key");
        assertThat(summary.name()).isEqualTo("My Experiment");
    }
}
