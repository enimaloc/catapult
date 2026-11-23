package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.BungieAccountData;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Container;
import fr.enimaloc.catapult.event.bungie.BungieAccountChangedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Bungie card: what each {@link BungieAccountData} shows, and that no token or internal id reaches it. */
class IntegrationCardsBungieTest {

    private static final Instant WHEN = Instant.parse("2026-11-20T10:00:00Z");

    private static List<IntegrationNode> all(List<IntegrationNode> nodes) {
        List<IntegrationNode> found = new ArrayList<>();
        for (IntegrationNode node : nodes) {
            found.add(node);
            if (node instanceof Container container) found.addAll(all(container.children()));
        }
        return found;
    }

    private static List<IntegrationNode> nodes(BungieAccountData data) {
        return all(IntegrationCards.bungie(data).nodes());
    }

    private static boolean shown(List<IntegrationNode> nodes, String idOrClass) {
        IntegrationNode node = nodes.stream().filter(n -> idOrClass.equals(n.attrs().id())
                || idOrClass.equals(n.attrs().className())).findFirst().orElseThrow();
        return node.attrs().state() == null || node.attrs().state().visible();
    }

    @Test
    void unlinked_offersTheLink_whenBungieIsSetUp() {
        List<IntegrationNode> card = nodes(BungieAccountData.unlinked(true));

        assertThat(shown(card, "bungie-connect-btn")).isTrue();
        assertThat(shown(card, "bungie-disconnect-btn")).isFalse();
        assertThat(shown(card, "bungie-not-connected-chip")).isTrue();
        assertThat(shown(card, "bungie-unavailable")).isFalse();
    }

    @Test
    void withoutABungieApplication_theLinkIsNotOffered() {
        List<IntegrationNode> card = nodes(new BungieAccountData(true, "G#0042", "steam", false, false, WHEN));

        assertThat(shown(card, "bungie-connect-btn")).isFalse();
        assertThat(shown(card, "bungie-disconnect-btn")).isTrue();
        assertThat(shown(card, "bungie-unavailable")).isTrue();
    }

    @Test
    void linked_showsTheBungieName_andUnlink() {
        List<IntegrationNode> card = nodes(new BungieAccountData(true, "G#0042", "steam", false, true, WHEN));

        assertThat(shown(card, "bungie-connected-chip")).isTrue();
        assertThat(shown(card, "bungie-account")).isTrue();
        assertThat(shown(card, "bungie-connect-btn")).isFalse();
        assertThat(shown(card, "bungie-disconnect-btn")).isTrue();
        assertThat(shown(card, "bungie-no-profile")).isFalse();
        assertThat(IntegrationCards.bungie(new BungieAccountData(true, "G#0042", "steam", false, true, WHEN))
                .toString()).contains("G#0042");
    }

    @Test
    void aRefusedLink_asksToLinkAgain() {
        List<IntegrationNode> card = nodes(new BungieAccountData(true, "G#0042", "steam", true, true, WHEN));

        assertThat(shown(card, "bungie-invalid")).isTrue();
        assertThat(shown(card, "bungie-connect-btn")).isTrue();
        assertThat(shown(card, "bungie-connected-chip")).isFalse();
    }

    @Test
    void anAccountWithoutDestiny2_saysSo() {
        assertThat(shown(nodes(new BungieAccountData(true, "G#0042", null, false, true, WHEN)), "bungie-no-profile"))
                .isTrue();
    }

    @Test
    void theEvent_carriesNamesAndFlagsOnly() {
        assertThat(java.util.Arrays.stream(BungieAccountChangedEvent.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("username", "linked", "usable", "account", "invalid", "noProfile", "available",
                        "canLink");
        BungieAccountChangedEvent unlinked = BungieAccountChangedEvent.of("jeb_", BungieAccountData.unlinked(false));
        assertThat(unlinked.account()).isEmpty();
        assertThat(unlinked.canLink()).isFalse();
    }
}
