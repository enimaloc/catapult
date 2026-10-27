package fr.enimaloc.catapult.service.steam;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SteamLanguageTest {

    @Test
    void fromLocale_french_mapsToFrench() {
        assertThat(SteamLanguage.fromLocale(Locale.FRENCH)).isEqualTo(SteamLanguage.FRENCH);
    }

    @Test
    void fromLocale_portugueseBrazil_mapsToBrazilian() {
        assertThat(SteamLanguage.fromLocale(Locale.of("pt", "BR"))).isEqualTo(SteamLanguage.BRAZILIAN);
    }

    @Test
    void fromLocale_portuguesePortugal_mapsToPortuguese() {
        assertThat(SteamLanguage.fromLocale(Locale.of("pt", "PT"))).isEqualTo(SteamLanguage.PORTUGUESE);
    }

    @Test
    void fromLocale_traditionalChinese_mapsToTchinese() {
        assertThat(SteamLanguage.fromLocale(Locale.of("zh", "TW"))).isEqualTo(SteamLanguage.TCHINESE);
    }

    @Test
    void fromLocale_simplifiedChinese_mapsToSchinese() {
        assertThat(SteamLanguage.fromLocale(Locale.of("zh", "CN"))).isEqualTo(SteamLanguage.SCHINESE);
    }

    @Test
    void fromLocale_unmappedLanguage_fallsBackToEnglish() {
        assertThat(SteamLanguage.fromLocale(Locale.of("xx"))).isEqualTo(SteamLanguage.ENGLISH);
    }

    @Test
    void fromLocale_null_fallsBackToEnglish() {
        assertThat(SteamLanguage.fromLocale(null)).isEqualTo(SteamLanguage.ENGLISH);
    }

    @Test
    void toString_isSteamStoreApiLanguageName() {
        assertThat(SteamLanguage.FRENCH).hasToString("french");
        assertThat(SteamLanguage.TCHINESE).hasToString("tchinese");
    }
}
