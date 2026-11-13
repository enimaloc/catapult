package fr.enimaloc.catapult.bgs.client;

import bgs.protocol.account.v2.GameAccountHandleOuterClass.GameAccountHandle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static fr.enimaloc.catapult.bgs.client.ClientHarness.handle;
import static org.assertj.core.api.Assertions.assertThat;

class GameAccountSelectorTest {

    private static final GameAccountHandle APP_US = handle(1, "App", 1);
    private static final GameAccountHandle WTCG_US = handle(2, "WTCG", 1);
    private static final GameAccountHandle WTCG_EU = handle(3, "WTCG", 2);
    private static final GameAccountHandle APP_EU = handle(4, "App", 2);

    @Test
    void launcherInTheConnectedRegionComesFirst() {
        assertThat(GameAccountSelector.select(List.of(APP_US, WTCG_EU, APP_EU), 2)).isEqualTo(APP_EU);
    }

    @Test
    void thenAnyTitleInTheConnectedRegion() {
        assertThat(GameAccountSelector.select(List.of(APP_US, WTCG_EU), 2)).isEqualTo(WTCG_EU);
    }

    @Test
    void thenTheLauncherInAnotherRegion() {
        assertThat(GameAccountSelector.select(List.of(WTCG_US, APP_US), 2)).isEqualTo(APP_US);
    }

    @Test
    void thenTheFirstCandidate() {
        assertThat(GameAccountSelector.select(List.of(WTCG_US, handle(5, "Pro", 1)), 2)).isEqualTo(WTCG_US);
    }

    @Test
    void unknownRegionCountsAsEuropeAndNoCandidateGivesNull() {
        assertThat(GameAccountSelector.select(List.of(APP_US, APP_EU), 0)).isEqualTo(APP_EU);
        assertThat(GameAccountSelector.select(List.of(), 2)).isNull();
    }
}
