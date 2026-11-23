package fr.enimaloc.catapult.common.dto.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BungieAccountDataTest {

    @Test
    void unlinked_isNothingButWhetherLinkingIsOffered() {
        assertThat(BungieAccountData.unlinked(true)).isEqualTo(new BungieAccountData(false, null, null, false, true,
                null));
        assertThat(BungieAccountData.unlinked(false).available()).isFalse();
    }
}
