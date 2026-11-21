package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.enrichment.GameEnrichment;
import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The v3 aggregated answer: the v2 objects plus the enrichment ones, and the summary fields derived from them. */
class GameInfoV3ResponseTest {

    private static final String BASE = "https://catapult.test";
    private static final MoreLinks LINKS = new MoreLinks(BASE, GameInfoAssembler.LinkStyle.CURRENT);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final GameInfoResponse.IgdbObject IGDB = new GameInfoResponse.IgdbObject("72", "portal-2",
            "A puzzle game.", "https://images.igdb.com/cover.jpg", List.of("Puzzle"), BASE + "/api/games/igdb/72",
            null);
    private static final GameInfoResponse BASE_V2 = new GameInfoResponse(true, IGDB, null, null, null, null,
            "Portal 2");

    private static final TimeToBeat TTB = new TimeToBeat(30_000L, 36_000L, 80_000L, 12, TimeToBeat.Origin.HLTB,
            "https://howlongtobeat.com/game/7231");
    private static final ProtonDbSummary PROTON = new ProtonDbSummary("platinum", "gold", "platinum", "strong",
            0.92, 450, "https://www.protondb.com/app/620");
    private static final SteamGridDbAssets SGDB = new SteamGridDbAssets(5L, "Portal 2",
            "https://www.steamgriddb.com/game/5",
            List.of(new SteamGridDbAssets.Asset("https://cdn.sgdb/grid.png", "https://cdn.sgdb/grid_t.png", 600,
                    900, "alternate")),
            List.of(), List.of(), List.of());
    private static final SteamDeckCompatibility DECK = new SteamDeckCompatibility(
            SteamDeckCompatibility.Category.VERIFIED, List.of());
    private static final PcGamingWikiPage PCGW = new PcGamingWikiPage(42L, "Portal 2",
            "https://www.pcgamingwiki.com/wiki/Portal_2", "true", "true", "false", List.of());
    private static final GameEnrichment.Trailer TRAILER = new GameEnrichment.Trailer("mp4",
            BASE + "/api/me/game/trailer.mp4");

    @Test
    void summaryView_containsNewDerivedFields_notObjects() {
        GameEnrichment enrichment = new GameEnrichment(TTB, PROTON, SGDB, DECK, PCGW, TRAILER, "620");
        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, enrichment, LINKS, Optional.of("72"));

        JsonNode summary = MAPPER.readTree(MAPPER.writerWithView(GameInfoResponse.Summary.class)
                .writeValueAsString(response));

        assertThat(summary.path("inGame").asBoolean()).isTrue();
        assertThat(summary.path("name").asString()).isEqualTo("Portal 2");
        assertThat(summary.path("description").asString()).isEqualTo("A puzzle game.");
        assertThat(summary.path("timeToBeat").asLong()).isEqualTo(36_000L);
        assertThat(summary.path("protonTier").asString()).isEqualTo("platinum");
        assertThat(summary.path("deckStatus").asString()).isEqualTo("verified");
        assertThat(summary.path("coverUrl").asString()).isEqualTo("https://cdn.sgdb/grid.png");
        assertThat(summary.path("trailer").path("kind").asString()).isEqualTo("mp4");
        assertThat(summary.path("trailer").path("url").asString()).isEqualTo(BASE + "/api/me/game/trailer.mp4");
        for (String object : List.of("igdb", "steam", "xbox", "dtdd", "catapult", "hltb", "protondb", "steamgriddb",
                "steamDeck", "pcgamingwiki", "base")) {
            assertThat(summary.has(object)).as(object).isFalse();
        }

        JsonNode full = MAPPER.readTree(MAPPER.writeValueAsString(response));
        assertThat(full.path("hltb").path("normally").asLong()).isEqualTo(36_000L);
        assertThat(full.path("hltb").path("origin").asString()).isEqualTo("hltb");
        assertThat(full.path("hltb").path("more").asString()).isEqualTo(BASE + "/api/games/hltb/72");
        assertThat(full.path("protondb").path("more").asString()).isEqualTo(BASE + "/api/games/protondb/620");
        assertThat(full.path("steamDeck").path("category").asString()).isEqualTo("verified");
        assertThat(full.path("steamDeck").path("more").asString()).isEqualTo(BASE + "/api/games/steamdeck/620");
        assertThat(full.path("steamgriddb").path("grid").asString()).isEqualTo("https://cdn.sgdb/grid.png");
        assertThat(full.path("steamgriddb").path("more").asString()).isEqualTo(BASE + "/api/games/steamgriddb/5");
        assertThat(full.path("pcgamingwiki").path("more").asString()).isEqualTo(BASE + "/api/games/pcgamingwiki/42");
        assertThat(full.path("igdb").path("igdbId").asString()).isEqualTo("72");
        assertThat(full.has("base")).isFalse();
    }

    @Test
    void coverUrl_fallsBackToIgdbCover() {
        GameEnrichment noGrid = new GameEnrichment(null, null, new SteamGridDbAssets(5L, "Portal 2",
                "https://www.steamgriddb.com/game/5", List.of(), List.of(), List.of(), List.of()),
                null, null, null, null);
        assertThat(GameInfoV3Response.of(BASE_V2, noGrid, LINKS, Optional.of("72")).coverUrl())
                .isEqualTo("https://images.igdb.com/cover.jpg");

        GameEnrichment nothing = new GameEnrichment(null, null, null, null, null, null, null);
        GameInfoV3Response bare = GameInfoV3Response.of(BASE_V2, nothing, LINKS, Optional.of("72"));
        assertThat(bare.coverUrl()).isEqualTo("https://images.igdb.com/cover.jpg");
        assertThat(bare.timeToBeat()).isNull();
        assertThat(bare.protonTier()).isNull();
        assertThat(bare.deckStatus()).isNull();
        assertThat(bare.trailer()).isNull();

        assertThat(GameInfoV3Response.notInGame().coverUrl()).isNull();
        assertThat(GameInfoV3Response.notInGame().inGame()).isFalse();
        assertThat(GameInfoV3Response.notInGame().name()).isNull();
    }

    @Test
    void hltbFoundByScrapeOnly_hasNoMoreLink() {
        GameEnrichment enrichment = new GameEnrichment(TTB, null, null, null, null, null, null);

        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, enrichment, LINKS, Optional.empty());

        assertThat(response.hltb()).isNotNull();
        assertThat(response.hltb().more()).isNull();
        assertThat(response.hltb().url()).isEqualTo("https://howlongtobeat.com/game/7231");
    }
}
