package fr.enimaloc.catapult.service.ankama;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnkamaTitlesTest {

    @Test
    void titles_knownAndUnknown() {
        assertThat(AnkamaTitles.displayName(3)).isEqualTo("Wakfu");
        assertThat(AnkamaTitles.displayName(42)).isEqualTo("Ankama game 42");
    }
}
