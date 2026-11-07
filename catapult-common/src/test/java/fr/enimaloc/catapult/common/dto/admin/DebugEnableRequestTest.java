package fr.enimaloc.catapult.common.dto.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebugEnableRequestTest {

    @Test
    void missingFields_maskSecretsAndReplayEveryPage() {
        assertThat(DebugEnableRequest.read(null, null)).isEqualTo(new DebugEnableRequest(false, 1.0));
    }

    @Test
    void givenFields_areKept() {
        assertThat(DebugEnableRequest.read(true, 0.25)).isEqualTo(new DebugEnableRequest(true, 0.25));
        assertThat(DebugEnableRequest.read(false, 0.0)).isEqualTo(new DebugEnableRequest(false, 0.0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.5, Double.NaN})
    void aReplayRateOutsideZeroToOne_isRejected(double rate) {
        assertThatThrownBy(() -> new DebugEnableRequest(false, rate)).isInstanceOf(IllegalArgumentException.class);
    }
}
