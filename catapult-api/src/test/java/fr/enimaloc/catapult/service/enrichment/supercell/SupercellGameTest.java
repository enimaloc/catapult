package fr.enimaloc.catapult.service.enrichment.supercell;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupercellGameTest {

    @Test
    void aGame_isFoundByItsKey_anyCase() {
        assertThat(SupercellGame.parse(" Clash-Royale ")).contains(SupercellGame.CLASH_ROYALE);
        assertThat(SupercellGame.parse("brawl-stars")).contains(SupercellGame.BRAWL_STARS);
        assertThat(SupercellGame.parse("hay-day")).isEmpty();
        assertThat(SupercellGame.parse(null)).isEmpty();
    }

    @Test
    void aGame_isFoundByItsTitle_anyCase() {
        assertThat(SupercellGame.byTitle("clash of clans")).contains(SupercellGame.CLASH_OF_CLANS);
        assertThat(SupercellGame.byTitle(" Brawl Stars ")).contains(SupercellGame.BRAWL_STARS);
        assertThat(SupercellGame.byTitle("Clash of Ninja")).isEmpty();
        assertThat(SupercellGame.byTitle(null)).isEmpty();
    }

    @Test
    void eachGame_hasItsHostAndScope() {
        assertThat(SupercellGame.CLASH_OF_CLANS.host()).isEqualTo("https://api.clashofclans.com");
        assertThat(SupercellGame.CLASH_ROYALE.scope()).isEqualTo("royale");
        assertThat(SupercellGame.BRAWL_STARS.key()).isEqualTo("brawl-stars");
        assertThat(SupercellGame.BRAWL_STARS.title()).isEqualTo("Brawl Stars");
    }
}
