package fr.enimaloc.catapult.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwMappingEntitiesTest {

    @Test
    void dtddTopicMapping_settersAndGetters() {
        TwDtddTopicMapping m = new TwDtddTopicMapping();
        m.setTwId("death_of_animal");
        m.setDtddTopicName("A dog dies");
        assertThat(m.getTwId()).isEqualTo("death_of_animal");
        assertThat(m.getDtddTopicName()).isEqualTo("A dog dies");
    }

    @Test
    void igdbDescriptorMapping_settersAndGetters() {
        TwIgdbDescriptorMapping m = new TwIgdbDescriptorMapping();
        m.setTwId("violence_graphic");
        m.setDescriptorId(42L);
        assertThat(m.getTwId()).isEqualTo("violence_graphic");
        assertThat(m.getDescriptorId()).isEqualTo(42L);
    }

    @Test
    void steamContentIdMapping_settersAndGetters() {
        TwSteamContentIdMapping m = new TwSteamContentIdMapping();
        m.setTwId("violence_graphic");
        m.setSteamContentId(2);
        assertThat(m.getTwId()).isEqualTo("violence_graphic");
        assertThat(m.getSteamContentId()).isEqualTo(2);
    }

    @Test
    void steamKeyword_settersAndGetters() {
        TwSteamKeyword m = new TwSteamKeyword();
        m.setTwId("violence_graphic");
        m.setKeyword("gore");
        assertThat(m.getTwId()).isEqualTo("violence_graphic");
        assertThat(m.getKeyword()).isEqualTo("gore");
    }
}
