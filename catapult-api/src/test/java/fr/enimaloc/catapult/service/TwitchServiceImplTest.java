package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwitchServiceImplTest {

    @Test
    void normalizeTitle_stripsPunctuationAndLowercases() {
        assertThat(TwitchServiceImpl.normalizeTitle("Grand Theft Auto: Vice City"))
            .isEqualTo("grandtheftautovicecity");
    }

    @Test
    void normalizeTitle_matchesArabicAndRomanNumeralTitles() {
        // The bug: IGDB names this game "Red Dead Redemption 2", Twitch names its
        // category "Red Dead Redemption II" — both must normalize identically.
        assertThat(TwitchServiceImpl.normalizeTitle("Red Dead Redemption 2"))
            .isEqualTo(TwitchServiceImpl.normalizeTitle("Red Dead Redemption II"));
    }

    @Test
    void normalizeTitle_convertsVariousRomanNumerals() {
        assertThat(TwitchServiceImpl.normalizeTitle("Civilization VI"))
            .isEqualTo(TwitchServiceImpl.normalizeTitle("Civilization 6"));
        assertThat(TwitchServiceImpl.normalizeTitle("Final Fantasy XIV"))
            .isEqualTo(TwitchServiceImpl.normalizeTitle("Final Fantasy 14"));
        assertThat(TwitchServiceImpl.normalizeTitle("Diablo III"))
            .isEqualTo(TwitchServiceImpl.normalizeTitle("Diablo 3"));
    }

    @Test
    void normalizeTitle_leavesAlreadyArabicTitlesUnchanged() {
        assertThat(TwitchServiceImpl.normalizeTitle("Dirt 5")).isEqualTo("dirt5");
        assertThat(TwitchServiceImpl.normalizeTitle("FIFA 21")).isEqualTo("fifa21");
    }

    @Test
    void normalizeTitle_doesNotAffectTitlesWithNoNumeralWords() {
        assertThat(TwitchServiceImpl.normalizeTitle("Half-Life")).isEqualTo("halflife");
        assertThat(TwitchServiceImpl.normalizeTitle("Minecraft")).isEqualTo("minecraft");
    }
}
