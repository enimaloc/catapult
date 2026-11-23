package fr.enimaloc.catapult.service.enrichment.supercell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupercellTagTest {

    @Test
    void whatAMemberTypes_isNormalized() {
        assertThat(SupercellTag.normalize("#2PP")).contains("#2PP");
        assertThat(SupercellTag.normalize(" 2pp ")).contains("#2PP");
        assertThat(SupercellTag.normalize("#p0 lq")).contains("#P0LQ");
        // the letter O is read as the digit 0
        assertThat(SupercellTag.normalize("#9OYY")).contains("#90YY");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"#", "#2P", "#ABC", "#2PP/x", "##2PP", "#2PPPPPPPPPPPPP", "%232PP"})
    void anythingElse_isNoTag(String raw) {
        assertThat(SupercellTag.normalize(raw)).isEmpty();
    }

    @Test
    void aVeryLongInput_isNoTag() {
        assertThat(SupercellTag.normalize(" ".repeat(40) + "#2PP")).isEmpty();
    }

    @Test
    void theHash_isEncodedInThePath() {
        assertThat(SupercellTag.pathSegment("#2PP")).isEqualTo("%232PP");
        assertThatThrownBy(() -> SupercellTag.pathSegment("2PP")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupercellTag.pathSegment(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
