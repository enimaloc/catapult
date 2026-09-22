package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SteamLanguageTest {

    @Test
    void fromLocale_french_mapsToFrench() {
        assertThat(SteamLanguage.fromLocale(Locale.FRENCH)).hasToString("french");
    }

    @Test
    void fromLocale_portugueseBrazil_mapsToBrazilian() {
        assertThat(SteamLanguage.fromLocale(Locale.of("pt", "BR"))).hasToString("brazilian");
    }

    @Test
    void fromLocale_portuguesePortugal_mapsToPortuguese() {
        assertThat(SteamLanguage.fromLocale(Locale.of("pt", "PT"))).hasToString("portuguese");
    }

    @Test
    void fromLocale_traditionalChinese_mapsToTchinese() {
        assertThat(SteamLanguage.fromLocale(Locale.of("zh", "TW"))).hasToString("tchinese");
    }

    @Test
    void fromLocale_simplifiedChinese_mapsToSchinese() {
        assertThat(SteamLanguage.fromLocale(Locale.of("zh", "CN"))).hasToString("schinese");
    }

    @Test
    void fromLocale_unmappedLanguage_fallsBackToEnglish() {
        assertThat(SteamLanguage.fromLocale(Locale.of("xx"))).hasToString("english");
    }

    @Test
    void fromLocale_null_fallsBackToEnglish() {
        assertThat(SteamLanguage.fromLocale(null)).hasToString("english");
    }
}
