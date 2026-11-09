package fr.enimaloc.catapult.monitoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenParameterMaskTest {

    private static final String TOKEN = "catr_" + "r".repeat(43);

    @Test
    void tokenQueryParam_isMasked() {
        assertThat(TokenParameterMask.mask("token=" + TOKEN)).isEqualTo("token=***");
        assertThat(TokenParameterMask.mask("a=1&token=" + TOKEN + "&b=2")).isEqualTo("a=1&token=***&b=2");
        assertThat(TokenParameterMask.mask("https://catapult.test/api/me/game?token=" + TOKEN + "#top"))
                .isEqualTo("https://catapult.test/api/me/game?token=***#top");
    }

    @Test
    void otherParameters_stayAsTheyAre() {
        assertThat(TokenParameterMask.mask("widget_token=abc&tokens=def&q=token")).isEqualTo("widget_token=abc&tokens=def&q=token");
        assertThat(TokenParameterMask.mask(null)).isNull();
    }
}
