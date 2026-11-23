package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.RiotData;
import fr.enimaloc.catapult.dto.integration.IntegrationDto;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Chip;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Container;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Input;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Select;
import fr.enimaloc.catapult.dto.integration.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Riot card: which Riot ID, note and buttons each {@link RiotData} shows. */
class IntegrationCardsRiotTest {

    private static List<IntegrationNode> all(List<IntegrationNode> nodes) {
        List<IntegrationNode> found = new ArrayList<>();
        for (IntegrationNode node : nodes) {
            found.add(node);
            if (node instanceof Container container) found.addAll(all(container.children()));
        }
        return found;
    }

    private static List<IntegrationNode> nodes(RiotData riot) {
        return all(IntegrationCards.riot(riot).nodes());
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
    void aDeclaredRiotId_showsAsAChip_withItsPlatform_andTheDeleteButton() {
        List<IntegrationNode> nodes = nodes(new RiotData("Catapult#EUW", "na1", true));

        assertThat(shown(byClass(nodes, "riot-account"))).isTrue();
        Chip chip = (Chip) byClass(nodes, "riot-account-name");
        assertThat(chip.text()).isEqualTo(Message.literal("Catapult#EUW"));
        assertThat(shown(byClass(nodes, "riot-none"))).isFalse();
        assertThat(shown(byClass(nodes, "riot-unavailable"))).isFalse();
        assertThat(((Input) byId(nodes, "riot-id-input")).value()).isEqualTo("Catapult#EUW");
        Select platform = (Select) byId(nodes, "riot-platform-select");
        assertThat(platform.value()).isEqualTo("na1");
        assertThat(platform.options()).extracting(Select.Option::value).contains("euw1", "na1", "kr", "oc1")
                .hasSize(17);
        assertThat(shown(byId(nodes, "riot-delete-btn"))).isTrue();
        assertThat(shown(byId(nodes, "riot-save-btn"))).isTrue();
    }

    @Test
    void noRiotId_saysSo_withEuw1Picked_andNoDeleteButton() {
        List<IntegrationNode> nodes = nodes(new RiotData(null, null, true));

        assertThat(shown(byClass(nodes, "riot-account"))).isFalse();
        assertThat(shown(byClass(nodes, "riot-none"))).isTrue();
        assertThat(((Select) byId(nodes, "riot-platform-select")).value()).isEqualTo("euw1");
        assertThat(shown(byId(nodes, "riot-delete-btn"))).isFalse();
    }

    @Test
    void withoutAKeyInThePool_theCardSaysTheStatsAreOff() {
        List<IntegrationNode> nodes = nodes(new RiotData(null, null, false));

        assertThat(shown(byClass(nodes, "riot-unavailable"))).isTrue();
    }

    @Test
    void theCardIsWide_andTitledRiot() {
        IntegrationDto card = IntegrationCards.riot(new RiotData(null, null, true));

        assertThat(card.wide()).isTrue();
        assertThat(card.id()).isEqualTo("riot");
    }
}
