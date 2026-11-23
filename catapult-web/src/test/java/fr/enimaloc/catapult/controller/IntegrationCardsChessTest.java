package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.ChessData;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
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

/** The chess card: which account, note, field and buttons each {@link ChessData} shows, per site. */
class IntegrationCardsChessTest {

    private static final String PREFIX = "channel.connections.chess.";

    private static ChessData data(String chesscom, String lichess) {
        return new ChessData(chesscom, lichess);
    }

    private static List<IntegrationNode> all(List<IntegrationNode> nodes) {
        List<IntegrationNode> found = new ArrayList<>();
        for (IntegrationNode node : nodes) {
            found.add(node);
            if (node instanceof Container container) found.addAll(all(container.children()));
        }
        return found;
    }

    private static List<IntegrationNode> nodes(ChessData chess) {
        return all(IntegrationCards.chess(chess).nodes());
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
    void eachDeclaredAccountShowsAsAChipThatFollowsTheEvent() {
        List<IntegrationNode> both = nodes(data("hikaru", "DrNykterstein"));

        Chip chesscom = (Chip) byClass(both, "chess-chesscom-account-name");
        assertThat(chesscom.text()).isEqualTo(Message.literal("hikaru"));
        assertThat(chesscom.attrs().state().dataValue())
                .isEqualTo("textContent:ChessAccountChangedEvent.chesscomUsername");
        Chip lichess = (Chip) byClass(both, "chess-lichess-account-name");
        assertThat(lichess.text()).isEqualTo(Message.literal("DrNykterstein"));
        assertThat(shown(byClass(both, "chess-chesscom-account"))).isTrue();
        assertThat(shown(byClass(both, "chess-chesscom-none"))).isFalse();
        assertThat(shown(byClass(both, "chess-lichess-none"))).isFalse();
    }

    @Test
    void aSiteWithoutAccountSaysSoAndHidesItsRemoveButton() {
        List<IntegrationNode> onlyLichess = nodes(data(null, "DrNykterstein"));

        assertThat(shown(byClass(onlyLichess, "chess-chesscom-account"))).isFalse();
        Text none = (Text) byClass(onlyLichess, "chess-chesscom-none");
        assertThat(shown(none)).isTrue();
        assertThat(none.text()).isEqualTo(Message.key(PREFIX + "chesscom.none"));
        assertThat(shown(byId(onlyLichess, "chess-chesscom-delete-btn"))).isFalse();
        assertThat(shown(byId(onlyLichess, "chess-chesscom-save-btn"))).isTrue();
        assertThat(shown(byId(onlyLichess, "chess-lichess-delete-btn"))).isTrue();
    }

    @Test
    void eachFieldHoldsItsDeclaredName() {
        List<IntegrationNode> both = nodes(data("hikaru", null));

        Input chesscom = (Input) byId(both, "chess-chesscom-username-input");
        assertThat(chesscom.value()).isEqualTo("hikaru");
        assertThat(chesscom.attrs().state().dataValue())
                .isEqualTo("value:ChessAccountChangedEvent.chesscomUsername");
        assertThat(((Input) byId(both, "chess-lichess-username-input")).value()).isNull();
    }

    @Test
    void cardSpansTheGrid() {
        assertThat(IntegrationCards.chess(data(null, null)).wide()).isTrue();
    }

    private static ChannelPageData page(ChessData chess) {
        return new ChannelPageData(new ChannelUserDto("1", "2", "enimaloc", "img"), "enimaloc", chess != null,
                true, false, null, new PagedBindings(0, 0, 0, List.of()), List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, chess);
    }

    @Test
    void onlyTheOwnerGetsTheCard() {
        assertThat(IntegrationCards.of(page(null), Map.of(), null)).extracting(IntegrationDto::id)
                .doesNotContain("chess");
        assertThat(IntegrationCards.of(page(data(null, null)), Map.of(), null)).extracting(IntegrationDto::id)
                .contains("chess");
    }
}
