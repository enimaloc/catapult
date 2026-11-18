package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.bgs.client.FriendEvent;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A link and the friend events naming the same streamer share a key, whatever the BattleTag's case. */
class BattleNetDebugKeysTest {

    @Test
    void link_knownByItsAccountTagAndInvitation() {
        BattleNetFriendLink link = new BattleNetFriendLink();
        link.setBnetAccountId(42);
        link.setBattleTag("Streamer#1234");
        link.setInvitationId(7L);

        assertThat(BattleNetDebugKeys.of(link)).containsExactlyInAnyOrder("id:42", "tag:streamer#1234", "inv:7");
    }

    @Test
    void sentInvitation_matchesTheLinkByTagOrInvitation() {
        BattleNetFriendLink link = new BattleNetFriendLink();
        link.setBattleTag("Streamer#1234");

        assertThat(BattleNetDebugKeys.of(new FriendEvent.InvitationSent(7, "STREAMER#1234")))
                .containsAnyElementsOf(BattleNetDebugKeys.of(link));
    }

    @Test
    void friendAdded_matchesByAccount() {
        assertThat(BattleNetDebugKeys.of(new FriendEvent.FriendAdded(42, "Other#1")))
                .contains(BattleNetDebugKeys.account(42));
    }
}
