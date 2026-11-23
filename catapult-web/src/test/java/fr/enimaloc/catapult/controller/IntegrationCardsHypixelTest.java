package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.HypixelData;
import fr.enimaloc.catapult.dto.integration.IntegrationDto;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Chip;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Container;
import fr.enimaloc.catapult.dto.integration.Message;
import fr.enimaloc.catapult.event.hypixel.HypixelAccountChangedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Hypixel card: which account, note and buttons each {@link HypixelData} shows. */
class IntegrationCardsHypixelTest {

    static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    private static List<IntegrationNode> all(List<IntegrationNode> nodes) {
        List<IntegrationNode> found = new ArrayList<>();
        for (IntegrationNode node : nodes) {
            found.add(node);
            if (node instanceof Container container) found.addAll(all(container.children()));
        }
        return found;
    }

    private static List<IntegrationNode> nodes(HypixelData data) {
        return all(IntegrationCards.hypixel(data).nodes());
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
    void aDeclaredAccount_showsAsAChip_andTheDeleteButton() {
        List<IntegrationNode> nodes = nodes(new HypixelData("Notch", UUID, true));

        assertThat(shown(byClass(nodes, "hypixel-account"))).isTrue();
        assertThat(((Chip) byClass(nodes, "hypixel-account-name")).text()).isEqualTo(Message.literal("Notch"));
        assertThat(shown(byClass(nodes, "hypixel-none"))).isFalse();
        assertThat(shown(byClass(nodes, "hypixel-unavailable"))).isFalse();
        assertThat(shown(byId(nodes, "hypixel-delete-btn"))).isTrue();
        assertThat(shown(byId(nodes, "hypixel-save-btn"))).isTrue();
    }

    @Test
    void noAccount_saysSo_andNoDeleteButton() {
        List<IntegrationNode> nodes = nodes(new HypixelData(null, null, true));

        assertThat(shown(byClass(nodes, "hypixel-account"))).isFalse();
        assertThat(shown(byClass(nodes, "hypixel-none"))).isTrue();
        assertThat(shown(byId(nodes, "hypixel-delete-btn"))).isFalse();
    }

    @Test
    void withoutAKeyInThePool_theCardSaysTheStatsAreOff() {
        assertThat(shown(byClass(nodes(new HypixelData(null, null, false)), "hypixel-unavailable"))).isTrue();
    }

    @Test
    void theCardIsWide_andItsEventCarriesNoSecret() {
        IntegrationDto card = IntegrationCards.hypixel(new HypixelData(null, null, true));

        assertThat(card.wide()).isTrue();
        assertThat(card.id()).isEqualTo("hypixel");
        assertThat(HypixelAccountChangedEvent.of("streamer", new HypixelData(null, null, false)))
                .isEqualTo(new HypixelAccountChangedEvent("streamer", "", "", false));
        assertThat(java.util.Arrays.stream(HypixelAccountChangedEvent.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("username", "name", "uuid", "available");
    }
}
