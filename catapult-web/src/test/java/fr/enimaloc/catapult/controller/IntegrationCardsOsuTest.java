package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.OsuAccountData;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Container;
import fr.enimaloc.catapult.event.osu.OsuAccountChangedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The osu! card: what each {@link OsuAccountData} shows, and that no token or internal id reaches it. */
class IntegrationCardsOsuTest {

    private static final Instant WHEN = Instant.parse("2026-11-20T10:00:00Z");

    private static List<IntegrationNode> all(List<IntegrationNode> nodes) {
        List<IntegrationNode> found = new ArrayList<>();
        for (IntegrationNode node : nodes) {
            found.add(node);
            if (node instanceof Container container) found.addAll(all(container.children()));
        }
        return found;
    }

    private static List<IntegrationNode> nodes(OsuAccountData data) {
        return all(IntegrationCards.osu(data).nodes());
    }

    private static boolean shown(List<IntegrationNode> nodes, String idOrClass) {
        IntegrationNode node = nodes.stream().filter(n -> idOrClass.equals(n.attrs().id())
                || idOrClass.equals(n.attrs().className())).findFirst().orElseThrow();
        return node.attrs().state() == null || node.attrs().state().visible();
    }

    @Test
    void unlinked_offersTheLink_whenOsuIsSetUp() {
        List<IntegrationNode> card = nodes(OsuAccountData.unlinked(true));

        assertThat(shown(card, "osu-connect-btn")).isTrue();
        assertThat(shown(card, "osu-disconnect-btn")).isFalse();
        assertThat(shown(card, "osu-not-connected-chip")).isTrue();
        assertThat(shown(card, "osu-unavailable")).isFalse();
    }

    @Test
    void withoutAnOsuApplication_theLinkIsNotOffered() {
        List<IntegrationNode> card = nodes(new OsuAccountData(true, "Inspector", false, false, WHEN));

        assertThat(shown(card, "osu-connect-btn")).isFalse();
        assertThat(shown(card, "osu-disconnect-btn")).isTrue();
        assertThat(shown(card, "osu-unavailable")).isTrue();
    }

    @Test
    void linked_showsTheUsername_andUnlink() {
        List<IntegrationNode> card = nodes(new OsuAccountData(true, "Inspector", false, true, WHEN));

        assertThat(shown(card, "osu-connected-chip")).isTrue();
        assertThat(shown(card, "osu-account")).isTrue();
        assertThat(shown(card, "osu-connect-btn")).isFalse();
        assertThat(shown(card, "osu-disconnect-btn")).isTrue();
        assertThat(IntegrationCards.osu(new OsuAccountData(true, "Inspector", false, true, WHEN)).toString())
                .contains("Inspector");
    }

    @Test
    void aRefusedLink_asksToLinkAgain() {
        List<IntegrationNode> card = nodes(new OsuAccountData(true, "Inspector", true, true, WHEN));

        assertThat(shown(card, "osu-invalid")).isTrue();
        assertThat(shown(card, "osu-connect-btn")).isTrue();
        assertThat(shown(card, "osu-connected-chip")).isFalse();
    }

    @Test
    void theEvent_carriesNamesAndFlagsOnly() {
        assertThat(java.util.Arrays.stream(OsuAccountChangedEvent.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("username", "linked", "usable", "account", "invalid", "available", "canLink");
        OsuAccountChangedEvent unlinked = OsuAccountChangedEvent.of("jeb_", OsuAccountData.unlinked(false));
        assertThat(unlinked.account()).isEmpty();
        assertThat(unlinked.canLink()).isFalse();
    }
}
