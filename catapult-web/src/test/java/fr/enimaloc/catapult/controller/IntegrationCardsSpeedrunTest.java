package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.SpeedrunData;
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

/** The speedrun.com card: which sentence, warning and buttons each {@link SpeedrunData} shows. */
class IntegrationCardsSpeedrunTest {

    private static final String PREFIX = "channel.connections.";

    private static SpeedrunData data(String declared, boolean hasKey, boolean keyInvalid, String name, String source) {
        return new SpeedrunData(declared, hasKey, keyInvalid, hasKey ? name : null, name,
                name == null ? null : "https://www.speedrun.com/users/" + name, source);
    }

    private static List<IntegrationNode> all(List<IntegrationNode> nodes) {
        List<IntegrationNode> found = new ArrayList<>();
        for (IntegrationNode node : nodes) {
            found.add(node);
            if (node instanceof Container container) found.addAll(all(container.children()));
        }
        return found;
    }

    private static List<IntegrationNode> nodes(SpeedrunData speedrun) {
        return all(IntegrationCards.speedrun(speedrun).nodes());
    }

    private static boolean shown(IntegrationNode node) {
        return node.attrs().state() == null || node.attrs().state().visible();
    }

    private static IntegrationNode byId(List<IntegrationNode> nodes, String id) {
        return nodes.stream().filter(node -> id.equals(node.attrs().id())).findFirst().orElseThrow();
    }

    /** The "Account used" sentences showing on first render, by the key of their "via ..." part. */
    private static List<String> shownAccountSentences(SpeedrunData speedrun) {
        return nodes(speedrun).stream()
                .filter(node -> node instanceof Container c && "speedrun-account".equals(c.attrs().className())
                        && shown(node))
                .map(node -> ((Container) node).children().get(2).text().key())
                .toList();
    }

    @Test
    void cardShowsTwitchAccount() {
        SpeedrunData speedrun = data(null, false, false, "Linkus7", "twitch");

        assertThat(shownAccountSentences(speedrun)).containsExactly(PREFIX + "speedrun.via_twitch");
        Chip name = (Chip) nodes(speedrun).stream().filter(n -> n instanceof Chip && shown(n)).findFirst().orElseThrow();
        assertThat(name.text()).isEqualTo(Message.literal("Linkus7"));
        assertThat(name.attrs().state().dataValue()).isEqualTo("textContent:SpeedrunAccountChangedEvent.resolvedName");
    }

    @Test
    void cardShowsTheKeyAndTheDeclaredAccounts() {
        assertThat(shownAccountSentences(data("n1", true, false, "KeyOwner", "api_key")))
                .containsExactly(PREFIX + "speedrun.via_key");
        assertThat(shownAccountSentences(data("n1", false, false, "n1", "declared")))
                .containsExactly(PREFIX + "speedrun.via_declared");
    }

    @Test
    void cardSaysWhenNoAccountIsFound() {
        SpeedrunData none = data(null, false, false, null, null);

        assertThat(shownAccountSentences(none)).isEmpty();
        assertThat(nodes(none)).anyMatch(n -> n instanceof Text t && shown(t)
                && t.text().equals(Message.key(PREFIX + "speedrun.none")));
        assertThat(nodes(data(null, false, false, "x", "twitch"))).noneMatch(n -> n instanceof Text t && shown(t)
                && t.text().equals(Message.key(PREFIX + "speedrun.none")));
    }

    @Test
    void cardWarnsOnInvalidKey() {
        Text refused = (Text) nodes(data(null, false, true, null, null)).stream()
                .filter(n -> n instanceof Text t && "speedrun-key-invalid".equals(t.attrs().className()))
                .findFirst().orElseThrow();

        assertThat(shown(refused)).isTrue();
        assertThat(refused.text()).isEqualTo(Message.key(PREFIX + "speedrun.key.invalid"));
        Text fine = (Text) nodes(data(null, true, false, "k", "api_key")).stream()
                .filter(n -> n instanceof Text t && "speedrun-key-invalid".equals(t.attrs().className()))
                .findFirst().orElseThrow();
        assertThat(shown(fine)).isFalse();
    }

    @Test
    void usernameFieldHoldsTheDeclaredNameAndItsRemoveButtonFollowsIt() {
        List<IntegrationNode> declared = nodes(data("Linkus7", false, false, "Linkus7", "declared"));
        List<IntegrationNode> undeclared = nodes(data(null, false, false, null, null));

        assertThat(((Input) byId(declared, "speedrun-username-input")).value()).isEqualTo("Linkus7");
        assertThat(shown(byId(declared, "speedrun-username-delete-btn"))).isTrue();
        assertThat(shown(byId(undeclared, "speedrun-username-delete-btn"))).isFalse();
        assertThat(shown(byId(declared, "speedrun-username-save-btn"))).isTrue();
    }

    @Test
    void keyFieldGivesWayToItsDeleteButtonOnceSaved_andIsAPasswordField() {
        List<IntegrationNode> saved = nodes(data(null, true, false, "k", "api_key"));
        List<IntegrationNode> none = nodes(data(null, false, false, null, null));

        assertThat(((Input) byId(none, "speedrun-key-input")).inputType()).isEqualTo("password");
        assertThat(((Input) byId(none, "speedrun-key-input")).autocomplete()).isEqualTo("new-password");
        assertThat(((Input) byId(none, "speedrun-key-input")).value()).isNull();
        assertThat(shown(byId(none, "speedrun-key-input"))).isTrue();
        assertThat(shown(byId(none, "speedrun-key-save-btn"))).isTrue();
        assertThat(shown(byId(none, "speedrun-key-delete-btn"))).isFalse();
        assertThat(shown(byId(saved, "speedrun-key-input"))).isFalse();
        assertThat(shown(byId(saved, "speedrun-key-save-btn"))).isFalse();
        assertThat(shown(byId(saved, "speedrun-key-delete-btn"))).isTrue();
        assertThat(nodes(data(null, true, false, "k", "api_key"))).anyMatch(n -> n instanceof Text t
                && "speedrun-key-saved".equals(t.attrs().className()) && shown(t));
    }

    @Test
    void cardSpansTheGrid() {
        IntegrationDto card = IntegrationCards.speedrun(data(null, false, false, null, null));

        assertThat(card.id()).isEqualTo("speedrun");
        assertThat(card.wide()).isTrue();
    }

    private static ChannelPageData page(SpeedrunData speedrun) {
        return new ChannelPageData(new ChannelUserDto("1", "2", "enimaloc", "img"), "enimaloc", speedrun != null,
                true, false, null, new PagedBindings(0, 0, 0, List.of()), List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null, null, null, null, null, speedrun);
    }

    @Test
    void noCardForNonOwner() {
        assertThat(IntegrationCards.of(page(null), Map.of(), null)).extracting(IntegrationDto::id)
                .doesNotContain("speedrun");
        assertThat(IntegrationCards.of(page(data(null, false, false, null, null)), Map.of(), null))
                .extracting(IntegrationDto::id).contains("speedrun");
    }

    @Test
    void cardNeverCarriesAKey() {
        SpeedrunData saved = data("n1", true, false, "KeyOwner", "api_key");

        assertThat(IntegrationCards.speedrun(saved).toString()).doesNotContainIgnoringCase("apikey=")
                .doesNotContain("secret");
        assertThat(nodes(saved)).filteredOn(n -> n instanceof Input).allSatisfy(n -> assertThat(((Input) n).value())
                .isNotEqualTo("KeyOwner").isNotEqualTo("secret"));
    }
}
