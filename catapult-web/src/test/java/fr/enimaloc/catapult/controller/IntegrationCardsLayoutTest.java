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
