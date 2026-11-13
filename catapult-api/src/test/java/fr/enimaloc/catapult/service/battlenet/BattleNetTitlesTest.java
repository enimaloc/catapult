package fr.enimaloc.catapult.service.battlenet;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class BattleNetTitlesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "WTCG|Hearthstone",
            "Pro|Overwatch 2",
            "WoW|World of Warcraft",
            "Fen|Diablo IV",
            "D3|Diablo III",
            "OSI|Diablo II: Resurrected",
            "S2|StarCraft II",
            "Hero|Heroes of the Storm",
            "W3|Warcraft III",
            "ZZZZ|ZZZZ"
    })
    void displayNameMapsKnownFourccsAndFallsBackToTheFourcc(String fourcc, String name) {
        assertThat(BattleNetTitles.displayName(fourcc)).isEqualTo(name);
    }
}
