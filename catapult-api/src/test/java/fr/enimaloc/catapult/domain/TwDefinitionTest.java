package fr.enimaloc.catapult.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwDefinitionTest {

    @Test
    void defaults_areEnabledAndSortOrderZero() {
        TwDefinition def = new TwDefinition();
        assertThat(def.isEnabled()).isTrue();
        assertThat(def.getSortOrder()).isZero();
    }

    @Test
    void settersAndGetters_roundTrip() {
        TwDefinition def = new TwDefinition();
        def.setId("violence_graphic");
        def.setLabel("Violence (graphic)");
        def.setDescription("Blood, gore...");
        def.setSortOrder(5);
        def.setEnabled(false);
        assertThat(def.getId()).isEqualTo("violence_graphic");
        assertThat(def.getLabel()).isEqualTo("Violence (graphic)");
        assertThat(def.getDescription()).isEqualTo("Blood, gore...");
        assertThat(def.getSortOrder()).isEqualTo(5);
        assertThat(def.isEnabled()).isFalse();
    }
}
