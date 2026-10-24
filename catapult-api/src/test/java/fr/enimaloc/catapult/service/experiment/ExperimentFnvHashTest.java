package fr.enimaloc.catapult.service.experiment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the hash behind every draw: changing it would silently reshuffle live experiments. */
class ExperimentFnvHashTest {

    @Test
    void hash_matchesTheValuesOfTheOriginalFormula() {
        assertThat(ExperimentService.fnvHash("u1:variant", "exp")).isEqualTo(139468437);
        assertThat(ExperimentService.fnvHash("00000000-0000-0000-0000-000000000001", "new-dashboard")).isEqualTo(214375643);
        assertThat(ExperimentService.fnvHash("abc", "")).isEqualTo(271849507);
    }

    @Test
    void bucket_isTheHashModuloOneHundred() {
        assertThat(ExperimentService.fnvHash("u1:variant", "exp") % 100).isEqualTo(37);
        assertThat(ExperimentService.fnvHash("00000000-0000-0000-0000-000000000001", "new-dashboard") % 100).isEqualTo(43);
        assertThat(ExperimentService.fnvHash("abc", "") % 100).isEqualTo(7);
    }
}
