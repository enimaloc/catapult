package fr.enimaloc.catapult.service.enrichment.steamgriddb;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SteamGridDbNamesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Hades™|hades",
            "  Counter-Strike:   Global Offensive |counter-strike: global offensive",
            "The Witcher® 3: Wild Hunt – Game of the Year Edition|the witcher 3: wild hunt",
            "Dark Souls III (Deluxe Edition)|dark souls iii",
            "Cyberpunk 2077 GOTY|cyberpunk 2077",
            "Divinity: Original Sin 2 - Definitive Edition|divinity: original sin 2",
            "Fate/Grand Order|fate grand order",
            "Skyrim Special Edition|skyrim special edition",
            "Dark Souls: Remastered|dark souls: remastered",
            "Ultimate Edition|ultimate edition",
            "Half-Life 2: Episode One|half-life 2: episode one"})
    void searchTerm(String name, String term) {
        assertThat(SteamGridDbNames.searchTerm(name)).isEqualTo(term);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Pokémon: Red|Pokemon Red",
            "Marvel's Spider-Man Remastered|marvel s spider man remastered",
            "Hades™|HADES"})
    void key_foldsCaseAccentsAndPunctuation(String a, String b) {
        assertThat(SteamGridDbNames.key(a)).isEqualTo(SteamGridDbNames.key(b));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"Hades|Hades II", "Dark Souls|Dark Souls III", "Doom|Doom Eternal"})
    void key_keepsSequelsApart(String a, String b) {
        assertThat(SteamGridDbNames.key(a)).isNotEqualTo(SteamGridDbNames.key(b));
    }
}
