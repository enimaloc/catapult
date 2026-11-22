package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.BlizzardStatsData;
import fr.enimaloc.catapult.common.dto.channel.BlizzardStatsData.WowCharacterOption;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.dto.integration.IntegrationDto;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Chip;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Container;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Select;
import fr.enimaloc.catapult.dto.integration.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The Blizzard stats card: what each {@link BlizzardStatsData} shows, and that no token or internal id reaches it. */
class IntegrationCardsBlizzardTest {

    private static final String PREFIX = "channel.connections.blizzard-stats.";
    private static final List<WowCharacterOption> TWO = List.of(
            new WowCharacterOption("eu:1001", "Thrall — Draenor (80, Shaman)"),
            new WowCharacterOption("us:77", "Jaina — Proudmoore (70, Mage)"));

    private static BlizzardStatsData data(boolean linked, boolean imported, List<WowCharacterOption> characters,
                                          String main, boolean available) {
        return new BlizzardStatsData(linked, linked ? "eu" : null, imported, characters, main, available,
                imported ? Instant.parse("2026-11-20T10:00:00Z") : null);
    }

    private static List<IntegrationNode> all(List<IntegrationNode> nodes) {
        List<IntegrationNode> found = new ArrayList<>();
        for (IntegrationNode node : nodes) {
            found.add(node);
            if (node instanceof Container container) found.addAll(all(container.children()));
        }
        return found;
    }

    private static List<IntegrationNode> nodes(BlizzardStatsData blizzard) {
        return all(IntegrationCards.blizzardStats(blizzard).nodes());
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
    void cardAsksForTheBattleNetLinkFirst() {
        List<IntegrationNode> unlinked = nodes(data(false, false, List.of(), null, true));

        assertThat(shown(byClass(unlinked, "blizzard-stats-unlinked"))).isTrue();
        assertThat(((fr.enimaloc.catapult.dto.integration.IntegrationNode.Text)
                byClass(unlinked, "blizzard-stats-unlinked")).text()).isEqualTo(Message.key(PREFIX + "unlinked"));
        // The whole body (region, characters, every button) is out of sight.
        assertThat(shown(byId(unlinked, "blizzard-stats-body"))).isFalse();
        assertThat(shown(byClass(nodes(data(true, false, List.of(), null, true)), "blizzard-stats-unlinked")))
                .isFalse();
        assertThat(shown(byId(nodes(data(true, false, List.of(), null, true)), "blizzard-stats-body"))).isTrue();
    }

    @Test
    void cardOffersImportWhenNotImported() {
        List<IntegrationNode> fresh = nodes(data(true, false, List.of(), null, true));

        assertThat(shown(byId(fresh, "blizzard-import-btn"))).isTrue();
        assertThat(shown(byId(fresh, "blizzard-reimport-btn"))).isFalse();
        assertThat(shown(byId(fresh, "blizzard-forget-btn"))).isFalse();
        assertThat(shown(byClass(fresh, "blizzard-stats-imported"))).isFalse();
        assertThat(shown(byId(fresh, "blizzard-main-select"))).isFalse();
        assertThat(byId(fresh, "blizzard-import-btn").text()).isEqualTo(Message.key(PREFIX + "import"));
    }

    @Test
    void cardOffersReimportAndForgetOnceImported() {
        List<IntegrationNode> done = nodes(data(true, true, TWO, "eu:1001", true));

        assertThat(shown(byId(done, "blizzard-import-btn"))).isFalse();
        assertThat(shown(byId(done, "blizzard-reimport-btn"))).isTrue();
        assertThat(shown(byId(done, "blizzard-forget-btn"))).isTrue();
        assertThat(byId(done, "blizzard-reimport-btn").text()).isEqualTo(Message.key(PREFIX + "reimport"));
        assertThat(byId(done, "blizzard-forget-btn").text()).isEqualTo(Message.key(PREFIX + "forget"));
    }

    @Test
    void importChipShowsTheDayAndFollowsTheEvent() {
        List<IntegrationNode> done = nodes(data(true, true, TWO, "eu:1001", true));

        assertThat(shown(byClass(done, "blizzard-stats-imported"))).isTrue();
        Chip chip = (Chip) byClass(done, "blizzard-stats-imported-on");
        assertThat(chip.text()).isEqualTo(Message.literal("2026-11-20"));
        assertThat(chip.attrs().state().dataValue()).isEqualTo("textContent:BlizzardAccountChangedEvent.importedOn");
    }

    @Test
    void cardHidesTheImportWhenTheBlizzardApiIsNotSetUp() {
        List<IntegrationNode> off = nodes(data(true, false, List.of(), null, false));
        List<IntegrationNode> offImported = nodes(data(true, true, TWO, "eu:1001", false));

        assertThat(shown(byId(off, "blizzard-import-btn"))).isFalse();
        assertThat(shown(byId(offImported, "blizzard-reimport-btn"))).isFalse();
        assertThat(shown(byClass(off, "blizzard-stats-unavailable"))).isTrue();
        assertThat(shown(byClass(nodes(data(true, false, List.of(), null, true)), "blizzard-stats-unavailable")))
                .isFalse();
        // Forgetting the characters needs no Blizzard call: it stays.
        assertThat(shown(byId(offImported, "blizzard-forget-btn"))).isTrue();
    }

    @Test
    void cardListsCharactersAndMain() {
        Select main = (Select) byId(nodes(data(true, true, TWO, "us:77", true)), "blizzard-main-select");

        assertThat(main.options()).extracting(Select.Option::value).containsExactly("eu:1001", "us:77");
        assertThat(main.options().get(0).text()).isEqualTo(Message.literal("Thrall — Draenor (80, Shaman)"));
        assertThat(main.value()).isEqualTo("us:77");
        assertThat(shown(main)).isTrue();
        assertThat(main.attrs().state().dataValue()).contains("value:BlizzardAccountChangedEvent.wowMainKey");
    }

    @Test
    void regionSelectOffersTheFourRegionsAndPicksTheAccountsOne() {
        Select region = (Select) byId(nodes(data(true, false, List.of(), null, true)), "blizzard-region-select");

        assertThat(region.options()).extracting(Select.Option::value).containsExactly("eu", "us", "kr", "tw");
        assertThat(region.value()).isEqualTo("eu");
        assertThat(region.attrs().state().dataValue()).isEqualTo("value:BlizzardAccountChangedEvent.region");
    }

    @Test
    void cardSpansTheGrid() {
        IntegrationDto card = IntegrationCards.blizzardStats(data(true, false, List.of(), null, true));

        assertThat(card.id()).isEqualTo("blizzard-stats");
        assertThat(card.wide()).isTrue();
    }

    private static ChannelPageData page(BlizzardStatsData blizzard) {
        return new ChannelPageData(new ChannelUserDto("1", "2", "enimaloc", "img"), "enimaloc", blizzard != null,
                true, false, null, new PagedBindings(0, 0, 0, List.of()), List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, blizzard);
    }

    @Test
    void noCardForNonOwner() {
        assertThat(IntegrationCards.of(page(null), Map.of(), null)).extracting(IntegrationDto::id)
                .doesNotContain("blizzard-stats");
        assertThat(IntegrationCards.of(page(data(true, false, List.of(), null, true)), Map.of(), null))
                .extracting(IntegrationDto::id).contains("blizzard-stats");
    }

    @Test
    void dashboardNeverCarriesInternalIds() {
        // What the page and its events can hold: names, flags and character keys, no Battle.net account id, no token.
        assertThat(java.util.Arrays.stream(BlizzardStatsData.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("linked", "region", "wowImported", "characters", "wowMainKey", "available",
                        "wowImportedAt")
                .noneMatch(name -> name.toLowerCase().contains("bnet") || name.toLowerCase().contains("token"));
        String json = tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(data(true, true, TWO, "eu:1001", true));
        assertThat(json).doesNotContainIgnoringCase("bnetAccountId").doesNotContainIgnoringCase("token");
        assertThat(IntegrationCards.blizzardStats(data(true, true, TWO, "eu:1001", true)).toString())
                .doesNotContainIgnoringCase("token");
    }
}
