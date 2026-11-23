package fr.enimaloc.catapult.service.enrichment.riot;

import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform.Cluster;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class RiotPlatformTest {

    @Test
    void platforms_parseIgnoringCase_andKnowTheirCluster() {
        assertThat(RiotPlatform.parse(" EUW1 ")).contains(RiotPlatform.EUW1);
        assertThat(RiotPlatform.parse("na1").orElseThrow().cluster()).isEqualTo(Cluster.AMERICAS);
        assertThat(RiotPlatform.parse("kr").orElseThrow().cluster()).isEqualTo(Cluster.ASIA);
        assertThat(RiotPlatform.parse("oc1").orElseThrow().cluster()).isEqualTo(Cluster.SEA);
        assertThat(RiotPlatform.EUW1.host()).isEqualTo("https://euw1.api.riotgames.com");
        assertThat(RiotPlatform.parse("europe")).isEmpty();
        assertThat(RiotPlatform.parse(null)).isEmpty();
    }

    @Test
    void clusters_parse_andSeaAsksAsiaForAccounts() {
        assertThat(Cluster.parse("Europe")).contains(Cluster.EUROPE);
        assertThat(Cluster.parse("euw1")).isEmpty();
        assertThat(Cluster.parse(null)).isEmpty();
        assertThat(Cluster.SEA.accountCluster()).isEqualTo(Cluster.ASIA);
        assertThat(Cluster.EUROPE.accountCluster()).isEqualTo(Cluster.EUROPE);
        assertThat(Cluster.AMERICAS.host()).isEqualTo("https://americas.api.riotgames.com");
    }

    @Test
    void theDataDragonLocale_followsTheLanguage() {
        assertThat(RiotLocale.of(Locale.FRENCH)).isEqualTo("fr_FR");
        assertThat(RiotLocale.of(Locale.UK)).isEqualTo("en_GB");
        assertThat(RiotLocale.of(Locale.ENGLISH)).isEqualTo("en_US");
        assertThat(RiotLocale.of(Locale.forLanguageTag("pt-PT"))).isEqualTo("pt_BR");
        assertThat(RiotLocale.of(Locale.forLanguageTag("nl"))).isEqualTo("en_US");
        assertThat(RiotLocale.of(null)).isEqualTo("en_US");
    }
}
