package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.AnkamaData;
import fr.enimaloc.catapult.common.dto.channel.BattleNetData;
import fr.enimaloc.catapult.common.dto.channel.MinecraftData;
import fr.enimaloc.catapult.common.dto.channel.ObsData;
import fr.enimaloc.catapult.common.dto.channel.SteamData;
import fr.enimaloc.catapult.common.dto.channel.TwitchatData;
import fr.enimaloc.catapult.common.dto.channel.XboxData;
import fr.enimaloc.catapult.dto.integration.Attrs;
import fr.enimaloc.catapult.dto.integration.IntegrationDto;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The integrations tab's grid: a card that never shows more than two buttons at once sits in it, others span it. */
class IntegrationCardsLayoutTest {

    private static final int GRID_MAX_BUTTONS = 2;

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "PENDING", "INVITE_REJECTED", "ACCEPTED", "REMOVED", "FULL"})
    void statusCards_stayInTheGrid_withAtMostTwoButtonsInEveryStatus(String status) {
        for (IntegrationDto card : List.of(
                IntegrationCards.minecraft(new MinecraftData(status, "Steve", "bot")),
                IntegrationCards.battleNet(new BattleNetData(status, "Me#1234", "Bot#1111")),
                IntegrationCards.ankama(new AnkamaData(status, "Me#1234", "Bot#5091")))) {
            assertThat(card.wide()).as(card.id()).isFalse();
            assertThat(visibleButtons(card.nodes())).as(card.id() + " " + status).isLessThanOrEqualTo(GRID_MAX_BUTTONS);
        }
    }

    @Test
    void twitchAndXbox_stayInTheGrid() {
        for (IntegrationDto card : List.of(IntegrationCards.twitch(),
                IntegrationCards.xbox(new XboxData(true)), IntegrationCards.xbox(new XboxData(false)))) {
            assertThat(card.wide()).as(card.id()).isFalse();
            assertThat(visibleButtons(card.nodes())).as(card.id()).isLessThanOrEqualTo(GRID_MAX_BUTTONS);
        }
    }

    /** Steam shows up to three buttons once connected (unlink, its token, the cache refresh). */
    @Test
    void steam_spansTheGrid_becauseItShowsThreeButtonsOnceConnected() {
        IntegrationDto steam = IntegrationCards.steam(new SteamData(true, true, true, false, false, false, 5));

        assertThat(visibleButtons(steam.nodes())).isGreaterThan(GRID_MAX_BUTTONS);
        assertThat(steam.wide()).isTrue();
    }

    /** OBS's three fields and Twitchat's events form need the width, whatever their button count. */
    @Test
    void formCards_spanTheGrid() {
        assertThat(IntegrationCards.obs(new ObsData(false, "localhost", 4455, false)).wide()).isTrue();
        assertThat(IntegrationCards.twitchat(new TwitchatData(TwitchatData.AUTO, List.of()),
                new IntegrationNode.Text(Attrs.NONE, false, null)).wide()).isTrue();
    }

    /** The cards that only feed the API's personal stats go to the API tab, the detection ones stay on Integrations. */
    @Test
    void theCards_areSplitBetweenTheIntegrationsAndTheApiTabs_inTheirOrder() {
        List<IntegrationDto> cards = List.of(IntegrationCards.twitch(), card("speedrun"), card("xbox"),
                card("retroachievements"), card("blizzard-stats"), card("bungie"), card("riot"), card("faceit"),
                card("chess"), card("osu"), card("hypixel"), card("supercell"), card("steam"), card("battlenet"), card("epic"),
                card("ankama"), card("minecraft"), card("obs"), card("twitchat"));

        assertThat(IntegrationCards.integrationsTab(cards)).extracting(IntegrationDto::id).containsExactly(
                "twitch", "xbox", "steam", "battlenet", "epic", "ankama", "minecraft", "obs", "twitchat");
        assertThat(IntegrationCards.apiTab(cards)).extracting(IntegrationDto::id).containsExactly(
                "speedrun", "retroachievements", "blizzard-stats", "bungie", "riot", "faceit", "chess", "osu",
                "hypixel", "supercell");
    }

    private static IntegrationDto card(String id) {
        return new IntegrationDto(id, null, List.of(), false);
    }

    /** Every grid card's linking button looks the same: a filled one. */
    @Test
    void linkingButtons_areFilled() {
        assertThat(buttonKind(IntegrationCards.minecraft(new MinecraftData("NONE", null, null)).nodes(), "minecraft-enroll-btn"))
                .isEqualTo(IntegrationNode.Button.Kind.FILLED);
        assertThat(buttonKind(IntegrationCards.xbox(new XboxData(false)).nodes(), "xbox-connect-btn"))
                .isEqualTo(IntegrationNode.Button.Kind.FILLED);
        assertThat(buttonKind(IntegrationCards.battleNet(new BattleNetData("NONE", null, null)).nodes(), "battlenet-connect-btn"))
                .isEqualTo(IntegrationNode.Button.Kind.FILLED);
        assertThat(buttonKind(IntegrationCards.ankama(new AnkamaData("NONE", null, null)).nodes(), "ankama-connect-btn"))
                .isEqualTo(IntegrationNode.Button.Kind.FILLED);
    }

    private static IntegrationNode.Button.Kind buttonKind(List<IntegrationNode> nodes, String id) {
        for (IntegrationNode node : nodes) {
            if (node instanceof IntegrationNode.Button button && id.equals(button.attrs().id())) {
                return button.kind();
            }
            if (node instanceof IntegrationNode.Container container) {
                IntegrationNode.Button.Kind kind = buttonKind(container.children(), id);
                if (kind != null) {
                    return kind;
                }
            }
        }
        return null;
    }

    /** Buttons shown on first render: hidden ones, or ones inside a hidden container, don't count. */
    private static int visibleButtons(List<IntegrationNode> nodes) {
        int count = 0;
        for (IntegrationNode node : nodes) {
            if (node instanceof IntegrationNode.Button button && visible(button.attrs())) {
                count++;
            } else if (node instanceof IntegrationNode.Container container && visible(container.attrs())) {
                count += visibleButtons(container.children());
            }
        }
        return count;
    }

    private static boolean visible(Attrs attrs) {
        if (attrs.className() != null && List.of(attrs.className().split(" ")).contains("hidden")) {
            return false;
        }
        return attrs.state() == null || attrs.state().visible();
    }
}
