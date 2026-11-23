package fr.enimaloc.catapult.domain.supercell;

import fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SupercellAccountTest {

    @Test
    void eachGameKeepsItsOwnTagAndName_andForgettingTheLastEmptiesTheRow() {
        SupercellAccount row = new SupercellAccount(UUID.randomUUID());
        assertThat(row.empty()).isTrue();

        for (SupercellGame game : SupercellGame.values()) {
            row.set(game, "#" + game.ordinal() + "PP", "name-" + game.key());
        }
        for (SupercellGame game : SupercellGame.values()) {
            assertThat(row.tag(game)).isEqualTo("#" + game.ordinal() + "PP");
            assertThat(row.name(game)).isEqualTo("name-" + game.key());
        }

        row.set(SupercellGame.CLASH_OF_CLANS, null, "ignored");
        row.set(SupercellGame.CLASH_ROYALE, null, null);
        assertThat(row.tag(SupercellGame.CLASH_OF_CLANS)).isNull();
        assertThat(row.name(SupercellGame.CLASH_OF_CLANS)).isNull();
        assertThat(row.empty()).isFalse();
        row.set(SupercellGame.BRAWL_STARS, null, null);
        assertThat(row.empty()).isTrue();
    }
}
