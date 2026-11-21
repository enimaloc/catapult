package fr.enimaloc.catapult.service.enrichment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GameNamesTest {

    @Test
    void normalizesMarksAccentsPunctuation() {
        assertThat(GameNames.normalize("  Pokémon™: Legends — Arceus®  ")).isEqualTo("pokemon legends arceus");
    }

    @Test
    void keepsDigits() {
        assertThat(GameNames.normalize("Hades II")).isEqualTo("hades ii");
        assertThat(GameNames.normalize("Left 4 Dead 2")).isEqualTo("left 4 dead 2");
    }
}
