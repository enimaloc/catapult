package fr.enimaloc.catapult.simulation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A fictitious Twitch id is told from a real (numeric) one by its prefix alone. */
class SimulationGateTest {

    @Test
    void fictitious_prefix() {
        assertThat(SimulationGate.isFictitiousTwitchId("m123456")).isTrue();
        // Made before the prefix was shortened: still fictitious
        assertThat(SimulationGate.isFictitiousTwitchId("mock-ab12cd34")).isTrue();
        assertThat(SimulationGate.isFictitiousTwitchId("123456")).isFalse();
        assertThat(SimulationGate.isFictitiousTwitchId(null)).isFalse();
        // Any other id starting with an m is not one: test and perf fixtures use such ids for real accounts
        assertThat(SimulationGate.isFictitiousTwitchId("matrix-mod")).isFalse();
        assertThat(SimulationGate.isFictitiousTwitchId("m")).isFalse();
        assertThat(SimulationGate.isFictitiousTwitchId("m12a")).isFalse();
    }
}
