package fr.enimaloc.catapult.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GameBindingTwFieldsTest {

    @Test
    void defaults_twEnabledTrue_twOverrideFalse_twsEmpty() {
        GameBinding b = new GameBinding();
        assertThat(b.isTwEnabled()).isTrue();
        assertThat(b.isTwOverride()).isFalse();
        assertThat(b.getTws()).isEmpty();
    }

    @Test
    void setTws_persistsValues() {
        GameBinding b = new GameBinding();
        b.getTws().add("violence_graphic");
        b.setTwOverride(true);
        b.setTwEnabled(false);
        assertThat(b.getTws()).containsExactly("violence_graphic");
        assertThat(b.isTwOverride()).isTrue();
        assertThat(b.isTwEnabled()).isFalse();
    }
}
