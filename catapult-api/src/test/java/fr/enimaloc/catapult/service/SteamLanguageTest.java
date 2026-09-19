package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SteamLanguageTest {

    @Test
    void fromLocale_french_mapsToFrench() {
        assertThat(SteamLanguage.fromLocale(Locale.FRENCH)).isEqualTo("french");
    }

    @Test
    void fromLocale_portugueseBrazil_mapsToBrazilian() {
        assertThat(SteamLanguage.fromLocale(Locale.of("pt", "BR"))).isEqualTo("brazilian");
    }

    @Test
    void fromLocale_portuguesePortugal_mapsToPortuguese() {
        assertThat(SteamLanguage.fromLocale(Locale.of("pt", "PT"))).isEqualTo("portuguese");
    }

    @Test
    void fromLocale_traditionalChinese_mapsToTchinese() {
        assertThat(SteamLanguage.fromLocale(Locale.of("zh", "TW"))).isEqualTo("tchinese");
    }

    @Test
    void fromLocale_simplifiedChinese_mapsToSchinese() {
        assertThat(SteamLanguage.fromLocale(Locale.of("zh", "CN"))).isEqualTo("schinese");
    }

    @Test
    void fromLocale_unmappedLanguage_fallsBackToEnglish() {
        assertThat(SteamLanguage.fromLocale(Locale.of("xx"))).isEqualTo("english");
    }

    @Test
    void fromLocale_null_fallsBackToEnglish() {
        assertThat(SteamLanguage.fromLocale(null)).isEqualTo("english");
    }
}
