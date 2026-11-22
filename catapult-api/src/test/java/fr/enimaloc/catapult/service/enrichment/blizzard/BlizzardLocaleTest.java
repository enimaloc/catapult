package fr.enimaloc.catapult.service.enrichment.blizzard;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class BlizzardLocaleTest {

    @Test
    void localeFollowsRequestAndRegion() {
        assertThat(BlizzardLocale.of(Locale.FRENCH, BlizzardRegion.EU)).isEqualTo("fr_FR");
        assertThat(BlizzardLocale.of(Locale.GERMAN, BlizzardRegion.US)).isEqualTo("de_DE");
        assertThat(BlizzardLocale.of(Locale.of("es"), BlizzardRegion.EU)).isEqualTo("es_ES");
        assertThat(BlizzardLocale.of(Locale.ITALIAN, BlizzardRegion.EU)).isEqualTo("it_IT");
        assertThat(BlizzardLocale.of(Locale.of("pt"), BlizzardRegion.EU)).isEqualTo("pt_BR");
        assertThat(BlizzardLocale.of(Locale.of("ru"), BlizzardRegion.EU)).isEqualTo("ru_RU");
        assertThat(BlizzardLocale.of(Locale.KOREAN, BlizzardRegion.KR)).isEqualTo("ko_KR");
        assertThat(BlizzardLocale.of(Locale.CHINESE, BlizzardRegion.TW)).isEqualTo("zh_TW");
        assertThat(BlizzardLocale.of(Locale.ENGLISH, BlizzardRegion.EU)).isEqualTo("en_GB");
        assertThat(BlizzardLocale.of(Locale.ENGLISH, BlizzardRegion.US)).isEqualTo("en_US");
        assertThat(BlizzardLocale.of(Locale.of("xx"), BlizzardRegion.US)).isEqualTo("en_US");
        assertThat(BlizzardLocale.of(Locale.of("xx"), BlizzardRegion.EU)).isEqualTo("en_GB");
    }
}
