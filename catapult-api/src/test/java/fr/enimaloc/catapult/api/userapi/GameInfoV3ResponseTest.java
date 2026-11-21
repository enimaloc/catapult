package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.enrichment.GameEnrichment;
import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticGame;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.prices.GamePrices;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
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

    private static final TimeToBeat TTB = new TimeToBeat(30_000L, 36_000L, 80_000L, 12);
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
            "https://www.pcgamingwiki.com/wiki/Portal_2", "true", "true", "false");
    private static final GameEnrichment.Trailer TRAILER = new GameEnrichment.Trailer("mp4",
            BASE + "/api/me/game/trailer.mp4?version=3");

    @Test
    void summaryView_containsNewDerivedFields_notObjects() {
        GameEnrichment enrichment = new GameEnrichment(TTB, PROTON, SGDB, DECK, PCGW, TRAILER, "620", null, null, null);
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
        assertThat(summary.path("trailer").path("url").asString()).isEqualTo(BASE + "/api/me/game/trailer.mp4?version=3");
        for (String object : List.of("igdb", "steam", "xbox", "dtdd", "catapult", "hltb", "protondb", "steamgriddb",
                "steamDeck", "pcgamingwiki", "prices", "opencritic", "twitch", "base")) {
            assertThat(summary.has(object)).as(object).isFalse();
        }

        JsonNode full = MAPPER.readTree(MAPPER.writeValueAsString(response));
        assertThat(full.path("hltb").path("normally").asLong()).isEqualTo(36_000L);
        assertThat(full.path("hltb").has("origin")).isFalse();
        assertThat(full.path("hltb").has("url")).isFalse();
        assertThat(full.path("hltb").path("more").asString()).isEqualTo(BASE + "/api/games/hltb/72?version=3");
        assertThat(full.path("protondb").path("more").asString()).isEqualTo(BASE + "/api/games/protondb/620?version=3");
        assertThat(full.path("steamDeck").path("category").asString()).isEqualTo("verified");
        assertThat(full.path("steamDeck").path("more").asString()).isEqualTo(BASE + "/api/games/steamdeck/620?version=3");
        assertThat(full.path("steamgriddb").path("grid").asString()).isEqualTo("https://cdn.sgdb/grid.png");
        assertThat(full.path("steamgriddb").path("more").asString()).isEqualTo(BASE + "/api/games/steamgriddb/5?version=3");
        assertThat(full.path("pcgamingwiki").path("more").asString()).isEqualTo(BASE + "/api/games/pcgamingwiki/42?version=3");
        assertThat(full.path("igdb").path("igdbId").asString()).isEqualTo("72");
        assertThat(full.has("base")).isFalse();
    }

    @Test
    void coverUrl_fallsBackToIgdbCover() {
        GameEnrichment noGrid = new GameEnrichment(null, null, new SteamGridDbAssets(5L, "Portal 2",
                "https://www.steamgriddb.com/game/5", List.of(), List.of(), List.of(), List.of()),
                null, null, null, null, null, null, null);
        assertThat(GameInfoV3Response.of(BASE_V2, noGrid, LINKS, Optional.of("72")).coverUrl())
                .isEqualTo("https://images.igdb.com/cover.jpg");

        GameEnrichment nothing = new GameEnrichment(null, null, null, null, null, null, null, null, null, null);
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
    void hltbWithoutIgdbId_hasNoMoreLink() {
        GameEnrichment enrichment = new GameEnrichment(TTB, null, null, null, null, null, null, null, null, null);

        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, enrichment, LINKS, Optional.empty());

        assertThat(response.hltb()).isNotNull();
        assertThat(response.hltb().more()).isNull();
    }

    private static GamePrices.Deal deal(String price) {
        return new GamePrices.Deal("61", "Steam", new BigDecimal(price), new BigDecimal("39.99"), 50,
                "https://store/" + price);
    }

    private static GamePrices prices(List<GamePrices.Deal> deals) {
        return new GamePrices("itad-1", "US", "EUR", deals.isEmpty() ? null : deals.getFirst(), null, deals,
                "https://isthereanydeal.com/game/portal-2/");
    }

    private static GameEnrichment withNewSources(GamePrices prices) {
        return new GameEnrichment(null, null, null, null, null, null, "620", prices,
                new OpenCriticGame(7L, "Portal 2", 88, 95, "Mighty", 50, "https://opencritic.com/game/7/portal-2"),
                new TwitchActivity("490377", "Portal 2", 3, 1234L, false, List.of(), List.of()));
    }

    @Test
    void summaryCarriesPriceScoreViewers() {
        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, withNewSources(prices(List.of(deal("19.99")))),
                LINKS, Optional.of("72"));

        JsonNode summary = MAPPER.readTree(MAPPER.writerWithView(GameInfoResponse.Summary.class)
                .writeValueAsString(response));

        assertThat(summary.path("price").path("amount").decimalValue()).isEqualByComparingTo("19.99");
        assertThat(summary.path("price").path("currency").asString()).isEqualTo("EUR");
        assertThat(summary.path("price").path("cut").asInt()).isEqualTo(50);
        assertThat(summary.path("price").path("shop").asString()).isEqualTo("Steam");
        assertThat(summary.path("criticScore").asInt()).isEqualTo(88);
        assertThat(summary.path("liveViewers").asLong()).isEqualTo(1234L);
        for (String object : List.of("prices", "opencritic", "twitch")) {
            assertThat(summary.has(object)).as(object).isFalse();
        }
    }

    @Test
    void summaryWithoutBestPrice_priceIsNull() {
        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, withNewSources(prices(List.of())), LINKS,
                Optional.of("72"));

        assertThat(response.price()).isNull();
        assertThat(GameInfoV3Response.of(BASE_V2, GameEnrichment.EMPTY, LINKS, Optional.of("72")).criticScore())
                .isNull();
        assertThat(GameInfoV3Response.notInGame().liveViewers()).isNull();
    }

    @Test
    void aggregateKeepsFiveCheapestDeals() {
        List<GamePrices.Deal> eight = List.of(deal("1.00"), deal("2.00"), deal("3.00"), deal("4.00"), deal("5.00"),
                deal("6.00"), deal("7.00"), deal("8.00"));

        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, withNewSources(prices(eight)), LINKS,
                Optional.of("72"));

        assertThat(response.prices().deals()).hasSize(5).containsExactlyElementsOf(eight.subList(0, 5));
    }

    @Test
    void moreLinksCarryCountryAndLanguage() {
        GameEnrichment enrichment = withNewSources(prices(List.of(deal("19.99"))));

        GameInfoV3Response given = GameInfoV3Response.of(BASE_V2, enrichment, LINKS, Optional.of("72"), "US", "de");
        assertThat(given.prices().more()).isEqualTo(BASE + "/api/games/prices/itad-1?version=3&country=US");
        assertThat(given.twitch().more()).isEqualTo(BASE + "/api/games/twitch/490377?version=3&language=de");
        assertThat(given.opencritic().more()).isEqualTo(BASE + "/api/games/opencritic/7?version=3");

        GameInfoV3Response absent = GameInfoV3Response.of(BASE_V2, enrichment, LINKS, Optional.of("72"));
        assertThat(absent.prices().more()).isEqualTo(BASE + "/api/games/prices/itad-1?version=3");
        assertThat(absent.twitch().more()).isEqualTo(BASE + "/api/games/twitch/490377?version=3");
    }
}
