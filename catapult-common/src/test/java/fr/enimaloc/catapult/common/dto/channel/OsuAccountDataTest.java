package fr.enimaloc.catapult.common.dto.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OsuAccountDataTest {

    @Test
    void unlinked_isNothingButWhetherLinkingIsOffered() {
        assertThat(OsuAccountData.unlinked(true)).isEqualTo(new OsuAccountData(false, null, false, true, null));
        assertThat(OsuAccountData.unlinked(false).available()).isFalse();
    }
}
