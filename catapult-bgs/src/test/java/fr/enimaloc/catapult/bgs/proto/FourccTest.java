package fr.enimaloc.catapult.bgs.proto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FourccTest {

    @Test
    void fourccRoundTrips() {
        assertThat(Fourcc.of(4288624)).isEqualTo("App");
        assertThat(Fourcc.of(1465140039)).isEqualTo("WTCG");
        assertThat(Fourcc.toId("WTCG")).isEqualTo(1465140039);
        assertThat(Fourcc.toId("App")).isEqualTo(4288624);
    }

    @Test
    void zeroIsNotEmpty() {
        assertThat(Fourcc.of(0)).isEqualTo("0");
    }

    @Test
    void rejectsCodesLongerThanFourCharacters() {
        assertThatThrownBy(() -> Fourcc.toId("TOOLONG")).isInstanceOf(IllegalArgumentException.class);
    }
}
