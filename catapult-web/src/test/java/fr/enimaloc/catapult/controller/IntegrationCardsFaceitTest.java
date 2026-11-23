package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.FaceitData;
import fr.enimaloc.catapult.dto.integration.IntegrationDto;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Chip;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Container;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Input;
import fr.enimaloc.catapult.dto.integration.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The FACEIT card: which nickname, note and buttons each {@link FaceitData} shows. */
class IntegrationCardsFaceitTest {

    private static List<IntegrationNode> all(List<IntegrationNode> nodes) {
        List<IntegrationNode> found = new ArrayList<>();
        for (IntegrationNode node : nodes) {
            found.add(node);
            if (node instanceof Container container) found.addAll(all(container.children()));
        }
        return found;
    }

    private static List<IntegrationNode> nodes(FaceitData faceit) {
        return all(IntegrationCards.faceit(faceit).nodes());
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

    @Test
    void aDeclaredNickname_showsAsAChip_andTheDeleteButton() {
        List<IntegrationNode> nodes = nodes(new FaceitData("Catapult", "https://www.faceit.com/en/players/Catapult",
                true));

        assertThat(shown(byClass(nodes, "faceit-account"))).isTrue();
        Chip chip = (Chip) byClass(nodes, "faceit-account-name");
        assertThat(chip.text()).isEqualTo(Message.literal("Catapult"));
        assertThat(shown(byClass(nodes, "faceit-none"))).isFalse();
        assertThat(shown(byClass(nodes, "faceit-unavailable"))).isFalse();
        assertThat(((Input) byId(nodes, "faceit-nickname-input")).value()).isEqualTo("Catapult");
        assertThat(shown(byId(nodes, "faceit-delete-btn"))).isTrue();
        assertThat(shown(byId(nodes, "faceit-save-btn"))).isTrue();
    }

    @Test
    void noNickname_saysSo_withoutTheDeleteButton() {
        List<IntegrationNode> nodes = nodes(new FaceitData(null, null, true));

        assertThat(shown(byClass(nodes, "faceit-account"))).isFalse();
        assertThat(shown(byClass(nodes, "faceit-none"))).isTrue();
        assertThat(shown(byId(nodes, "faceit-delete-btn"))).isFalse();
    }

    @Test
    void withoutAKeyInThePool_theCardSaysTheStatsAreOff() {
        List<IntegrationNode> nodes = nodes(new FaceitData(null, null, false));

        assertThat(shown(byClass(nodes, "faceit-unavailable"))).isTrue();
    }

    @Test
    void theCardIsWide_andIdentifiedFaceit() {
        IntegrationDto card = IntegrationCards.faceit(new FaceitData(null, null, true));

        assertThat(card.wide()).isTrue();
        assertThat(card.id()).isEqualTo("faceit");
    }
}
