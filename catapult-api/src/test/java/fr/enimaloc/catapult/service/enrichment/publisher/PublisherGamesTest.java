package fr.enimaloc.catapult.service.enrichment.publisher;

import fr.enimaloc.catapult.domain.binding.GameBinding.SourceType;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BlizzardGame;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PublisherGamesTest {

    private static DetectedGame bnet(String id) {
        return new DetectedGame(id, SourceType.BATTLENET, id);
    }

    @Test
    void battleNetProductCodesMap() {
        assertThat(PublisherGames.blizzard(bnet("WoW"), null)).contains(BlizzardGame.WOW);
        assertThat(PublisherGames.blizzard(bnet("S2"), null)).contains(BlizzardGame.SC2);
        assertThat(PublisherGames.blizzard(bnet("D3"), null)).contains(BlizzardGame.D3);
        assertThat(PublisherGames.blizzard(bnet("WTCG"), null)).contains(BlizzardGame.HEARTHSTONE);
        assertThat(PublisherGames.blizzard(bnet("Pro"), null)).isEmpty();
    }

    @Test
    void igdbIdsMapWhenNotDetectedOnBattleNet() {
        DetectedGame manual = new DetectedGame("x", SourceType.MANUAL, "x");
        assertThat(PublisherGames.blizzard(manual, "123")).contains(BlizzardGame.WOW);
        assertThat(PublisherGames.blizzard(manual, "239")).contains(BlizzardGame.SC2);
        assertThat(PublisherGames.blizzard(manual, "120")).contains(BlizzardGame.D3);
        assertThat(PublisherGames.blizzard(manual, "1279")).contains(BlizzardGame.HEARTHSTONE);
        assertThat(PublisherGames.blizzard(manual, "1")).isEmpty();
        assertThat(PublisherGames.blizzard(manual, null)).isEmpty();
    }

    @Test
    void variantIsNotRetail() {
        DetectedGame variant = bnet("WoW:Forever");
        assertThat(PublisherGames.blizzard(variant, "123")).isEmpty();
        assertThat(PublisherGames.isBlizzardVariant(variant)).isTrue();
        assertThat(PublisherGames.isBlizzardVariant(bnet("WoW"))).isFalse();
    }

    @Test
    void gameNamesSerializeInLowercase() {
        assertThat(JsonMapper.builder().build().writeValueAsString(BlizzardGame.HEARTHSTONE))
                .isEqualTo("\"hearthstone\"");
    }

    @Test
    void destiny2_isRecognisedByItsIgdbIdOnly() {
        assertThat(PublisherGames.bungie("25657")).contains(PublisherGames.BungieGame.DESTINY2);
        // The expansions are other IGDB games: not the game being played
        assertThat(PublisherGames.bungie("250645")).isEmpty();
        assertThat(PublisherGames.bungie(null)).isEmpty();
        assertThat(JsonMapper.builder().build().writeValueAsString(PublisherGames.BungieGame.DESTINY2))
                .isEqualTo("\"destiny2\"");
    }

    @Test
    void riotGames_areTheirIgdbIds_andAnEmptyIdMapsNothing() {
        assertThat(PublisherGames.riot("115", "115", "", "126459")).contains(PublisherGames.RiotGame.LOL);
        assertThat(PublisherGames.riot("126459", "115", "", "126459")).contains(PublisherGames.RiotGame.VALORANT);
        assertThat(PublisherGames.riot("7", "115", "7", "126459")).contains(PublisherGames.RiotGame.TFT);
        assertThat(PublisherGames.riot("", "115", "", "126459")).isEmpty();
        assertThat(PublisherGames.riot(null, "115", "", "126459")).isEmpty();
        assertThat(PublisherGames.riot("123", "115", "", "126459")).isEmpty();
    }

    @Test
    void counterStrike2_isTheFaceitGame_bySteamAppIdOrIgdbId() {
        assertThat(PublisherGames.faceit(new DetectedGame("730", SourceType.STEAM, "CS2"), null, "242408"))
                .contains(PublisherGames.FaceitGame.CS2);
        DetectedGame manual = new DetectedGame("x", SourceType.MANUAL, "x");
        assertThat(PublisherGames.faceit(manual, "242408", "242408")).contains(PublisherGames.FaceitGame.CS2);
        assertThat(PublisherGames.faceit(manual, "1", "242408")).isEmpty();
        assertThat(PublisherGames.faceit(manual, "242408", "")).isEmpty();
        assertThat(PublisherGames.faceit(manual, null, "242408")).isEmpty();
        assertThat(PublisherGames.faceit(new DetectedGame("440", SourceType.STEAM, "TF2"), null, "242408")).isEmpty();
        // another store's 730 is not Counter-Strike 2
        assertThat(PublisherGames.faceit(new DetectedGame("730", SourceType.XBOX, "x"), null, "242408")).isEmpty();
        assertThat(PublisherGames.faceit(null, null, "242408")).isEmpty();
        assertThat(JsonMapper.builder().build().writeValueAsString(PublisherGames.FaceitGame.CS2))
                .isEqualTo("\"cs2\"");
    }

    @Test
    void osuAndLazer_byIgdbId() {
        assertThat(PublisherGames.osu("3012")).contains(PublisherGames.OsuGame.OSU);
        assertThat(PublisherGames.osu("412517")).contains(PublisherGames.OsuGame.OSU);
        assertThat(PublisherGames.osu("1")).isEmpty();
        assertThat(PublisherGames.osu(null)).isEmpty();
        assertThat(JsonMapper.builder().build().writeValueAsString(PublisherGames.OsuGame.OSU)).isEqualTo("\"osu\"");
    }
}
