package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.SupercellData;
import fr.enimaloc.catapult.dto.integration.IntegrationDto;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Chip;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Container;
import fr.enimaloc.catapult.dto.integration.Message;
import fr.enimaloc.catapult.event.supercell.SupercellAccountChangedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Supercell card: per game, which player, notes and buttons each {@link SupercellData} shows. */
class IntegrationCardsSupercellTest {

    private static List<IntegrationNode> all(List<IntegrationNode> nodes) {
        List<IntegrationNode> found = new ArrayList<>();
        for (IntegrationNode node : nodes) {
            found.add(node);
            if (node instanceof Container container) found.addAll(all(container.children()));
        }
        return found;
    }

    /** The nodes of one game's section. */
    private static List<IntegrationNode> game(SupercellData data, String game) {
        Container section = (Container) IntegrationCards.supercell(data).nodes().stream()
                .filter(node -> node.attrs().className() != null
                        && node.attrs().className().contains("supercell-" + game))
                .findFirst().orElseThrow();
        return all(section.children());
    }

    private static boolean shown(IntegrationNode node) {
        return node.attrs().state() == null || node.attrs().state().visible();
    }

    private static IntegrationNode byId(List<IntegrationNode> nodes, String id) {
        return nodes.stream().filter(node -> id.equals(node.attrs().id())).findFirst().orElseThrow();
    }

    private static IntegrationNode byClass(List<IntegrationNode> nodes, String className) {
        return nodes.stream().filter(node -> className.equals(node.attrs().className())).findFirst().orElseThrow();
    }

    private static final SupercellData DATA = new SupercellData(new SupercellData.Game("#2PP", "Chief", true),
            new SupercellData.Game(null, null, true), new SupercellData.Game("#9YY", null, false));

    @Test
    void aDeclaredPlayer_showsAsAChip_andTheDeleteButton() {
        List<IntegrationNode> nodes = game(DATA, "clash-of-clans");

        assertThat(shown(byClass(nodes, "supercell-account"))).isTrue();
        assertThat(((Chip) byClass(nodes, "supercell-account-name")).text()).isEqualTo(Message.literal("Chief (#2PP)"));
        assertThat(shown(byClass(nodes, "supercell-none"))).isFalse();
        assertThat(shown(byClass(nodes, "supercell-unavailable"))).isFalse();
        assertThat(shown(byId(nodes, "supercell-clash-of-clans-delete-btn"))).isTrue();
        assertThat(shown(byId(nodes, "supercell-clash-of-clans-save-btn"))).isTrue();
    }

    @Test
    void noPlayer_saysSo_andNoDeleteButton() {
        List<IntegrationNode> nodes = game(DATA, "clash-royale");

        assertThat(shown(byClass(nodes, "supercell-account"))).isFalse();
        assertThat(shown(byClass(nodes, "supercell-none"))).isTrue();
        assertThat(shown(byId(nodes, "supercell-clash-royale-delete-btn"))).isFalse();
    }

    @Test
    void aGameWithoutKey_saysItsStatsAreOff_andATagWithoutNameShowsAlone() {
        List<IntegrationNode> nodes = game(DATA, "brawl-stars");

        assertThat(shown(byClass(nodes, "supercell-unavailable"))).isTrue();
        assertThat(((Chip) byClass(nodes, "supercell-account-name")).text()).isEqualTo(Message.literal("#9YY"));
    }

    @Test
    void aMissingGame_isShownEmpty() {
        List<IntegrationNode> nodes = game(new SupercellData(null, null, null), "clash-of-clans");

        assertThat(shown(byClass(nodes, "supercell-none"))).isTrue();
        assertThat(shown(byClass(nodes, "supercell-unavailable"))).isTrue();
    }

    @Test
    void theCardIsWide_andItsEventCarriesNoSecret() {
        IntegrationDto card = IntegrationCards.supercell(DATA);

        assertThat(card.wide()).isTrue();
        assertThat(card.id()).isEqualTo("supercell");
        assertThat(SupercellAccountChangedEvent.of("streamer", DATA)).isEqualTo(new SupercellAccountChangedEvent(
                "streamer", "#2PP", "Chief (#2PP)", true, "", "", true, "#9YY", "#9YY", false));
        assertThat(SupercellAccountChangedEvent.of("streamer", new SupercellData(null, null, null)))
                .isEqualTo(new SupercellAccountChangedEvent("streamer", "", "", false, "", "", false, "", "", false));
        assertThat(SupercellAccountChangedEvent.player(null)).isEmpty();
    }
}
