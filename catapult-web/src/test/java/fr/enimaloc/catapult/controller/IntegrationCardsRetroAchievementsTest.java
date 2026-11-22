package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.RetroAchievementsData;
import fr.enimaloc.catapult.dto.integration.IntegrationDto;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Chip;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Container;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Input;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Text;
import fr.enimaloc.catapult.dto.integration.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The RetroAchievements card: which account, warning, note and buttons each {@link RetroAchievementsData} shows. */
class IntegrationCardsRetroAchievementsTest {

    private static final String PREFIX = "channel.connections.retroachievements.";

    private static RetroAchievementsData data(String name, boolean hasKey, boolean keyInvalid, boolean pool) {
        return new RetroAchievementsData(name, name == null ? null : "https://retroachievements.org/user/" + name,
                hasKey, keyInvalid, pool);
    }

    private static List<IntegrationNode> all(List<IntegrationNode> nodes) {
        List<IntegrationNode> found = new ArrayList<>();
        for (IntegrationNode node : nodes) {
            found.add(node);
            if (node instanceof Container container) found.addAll(all(container.children()));
        }
        return found;
    }

    private static List<IntegrationNode> nodes(RetroAchievementsData ra) {
        return all(IntegrationCards.retroAchievements(ra).nodes());
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
    void cardShowsDeclaredAccount() {
        List<IntegrationNode> bob = nodes(data("Bob", false, false, true));

        assertThat(shown(byClass(bob, "retroachievements-account"))).isTrue();
        Chip name = (Chip) byClass(bob, "retroachievements-account-name");
        assertThat(name.text()).isEqualTo(Message.literal("Bob"));
        assertThat(name.attrs().state().dataValue())
                .isEqualTo("textContent:RetroAchievementsAccountChangedEvent.declaredUsername");
        assertThat(((Text) ((Container) byClass(bob, "retroachievements-account")).children().get(0)).text())
                .isEqualTo(Message.key(PREFIX + "account"));
        assertThat(shown(byClass(bob, "retroachievements-none"))).isFalse();
    }

    @Test
    void cardSaysWhenThereIsNoAccount() {
        List<IntegrationNode> none = nodes(data(null, false, false, true));

        assertThat(shown(byClass(none, "retroachievements-account"))).isFalse();
        Text text = (Text) byClass(none, "retroachievements-none");
        assertThat(shown(text)).isTrue();
        assertThat(text.text()).isEqualTo(Message.key(PREFIX + "none"));
    }

    @Test
    void cardWarnsOnInvalidKey() {
        Text refused = (Text) byClass(nodes(data("Bob", false, true, true)), "retroachievements-key-invalid");

        assertThat(shown(refused)).isTrue();
        assertThat(refused.text()).isEqualTo(Message.key(PREFIX + "key.invalid"));
        assertThat(shown(byClass(nodes(data("Bob", true, false, true)), "retroachievements-key-invalid"))).isFalse();
    }

    @Test
    void cardAsksForAKeyWhenThePoolIsEmpty() {
        Text note = (Text) byClass(nodes(data("Bob", false, false, false)), "retroachievements-no-pool");

        assertThat(shown(note)).isTrue();
        assertThat(note.text()).isEqualTo(Message.key(PREFIX + "no_pool"));
        // A pool answers for the member, and so does their own key.
        assertThat(shown(byClass(nodes(data("Bob", false, false, true)), "retroachievements-no-pool"))).isFalse();
        assertThat(shown(byClass(nodes(data("Bob", true, false, false)), "retroachievements-no-pool"))).isFalse();
    }

    @Test
    void usernameFieldHoldsTheDeclaredNameAndItsRemoveButtonFollowsIt() {
        List<IntegrationNode> declared = nodes(data("Bob", false, false, true));
        List<IntegrationNode> undeclared = nodes(data(null, false, false, true));

        assertThat(((Input) byId(declared, "retroachievements-username-input")).value()).isEqualTo("Bob");
        assertThat(shown(byId(declared, "retroachievements-username-delete-btn"))).isTrue();
        assertThat(shown(byId(undeclared, "retroachievements-username-delete-btn"))).isFalse();
        assertThat(shown(byId(undeclared, "retroachievements-username-save-btn"))).isTrue();
    }

    @Test
    void keyFieldGivesWayToItsDeleteButtonOnceSaved_andIsAPasswordField() {
        List<IntegrationNode> saved = nodes(data("Bob", true, false, true));
        List<IntegrationNode> none = nodes(data("Bob", false, false, true));

        Input input = (Input) byId(none, "retroachievements-key-input");
        assertThat(input.inputType()).isEqualTo("password");
        assertThat(input.autocomplete()).isEqualTo("new-password");
        assertThat(input.value()).isNull();
        assertThat(shown(input)).isTrue();
        assertThat(shown(byId(none, "retroachievements-key-save-btn"))).isTrue();
        assertThat(shown(byId(none, "retroachievements-key-delete-btn"))).isFalse();
        assertThat(shown(byId(saved, "retroachievements-key-input"))).isFalse();
        assertThat(shown(byId(saved, "retroachievements-key-save-btn"))).isFalse();
        assertThat(shown(byId(saved, "retroachievements-key-delete-btn"))).isTrue();
        assertThat(shown(byClass(saved, "retroachievements-key-saved"))).isTrue();
    }

    @Test
    void cardSpansTheGrid() {
        IntegrationDto card = IntegrationCards.retroAchievements(data(null, false, false, true));

        assertThat(card.id()).isEqualTo("retroachievements");
        assertThat(card.wide()).isTrue();
    }

    private static ChannelPageData page(RetroAchievementsData ra) {
        return new ChannelPageData(new ChannelUserDto("1", "2", "enimaloc", "img"), "enimaloc", ra != null,
                true, false, null, new PagedBindings(0, 0, 0, List.of()), List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, ra);
    }

    @Test
    void noCardForNonOwner() {
        assertThat(IntegrationCards.of(page(null), Map.of(), null)).extracting(IntegrationDto::id)
                .doesNotContain("retroachievements");
        assertThat(IntegrationCards.of(page(data(null, false, false, true)), Map.of(), null))
                .extracting(IntegrationDto::id).contains("retroachievements");
    }

    @Test
    void cardNeverCarriesAKey() {
        RetroAchievementsData saved = data("Bob", true, false, true);

        assertThat(IntegrationCards.retroAchievements(saved).toString()).doesNotContainIgnoringCase("apikey=")
                .doesNotContain("secret");
        assertThat(nodes(saved)).filteredOn(n -> n instanceof Input).allSatisfy(n -> assertThat(((Input) n).value())
                .isNotEqualTo("secret"));
    }
}
