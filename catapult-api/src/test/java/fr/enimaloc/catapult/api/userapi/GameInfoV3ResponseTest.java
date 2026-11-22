package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.enrichment.GameEnrichment;
import fr.enimaloc.catapult.service.enrichment.PersonalStatsEnrichment;
import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticGame;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.prices.GamePrices;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunEnrichment;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunGame;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentity;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRecords;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRun;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.AchievementSummary;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.stream.IntStream;
import java.util.List;
import java.util.Map;
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
        GameEnrichment enrichment = new GameEnrichment(TTB, PROTON, SGDB, DECK, PCGW, TRAILER, "620", null, null, null, null, null);
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
                null, null, null, null, null, null, null, null, null);
        assertThat(GameInfoV3Response.of(BASE_V2, noGrid, LINKS, Optional.of("72")).coverUrl())
                .isEqualTo("https://images.igdb.com/cover.jpg");

        GameEnrichment nothing = new GameEnrichment(null, null, null, null, null, null, null, null, null, null, null, null);
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
        GameEnrichment enrichment = new GameEnrichment(TTB, null, null, null, null, null, null, null, null, null, null, null);

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
                new TwitchActivity("490377", "Portal 2", 3, 1234L, false, List.of(), List.of()), null, null);
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

    // ---------------------------------------------------------------- speedrun.com

    private static final SpeedrunGame CELESTE = new SpeedrunGame("o1y9j9v6", "Celeste", "Celeste", "celeste",
            "https://www.speedrun.com/celeste", "https://www.speedrun.com/static/game/o1y9j9v6/cover.png");
    private static final SpeedrunIdentity RUNNER = new SpeedrunIdentity("u1", "linkus7",
            "https://www.speedrun.com/users/linkus7", SpeedrunIdentity.Source.API_KEY);

    private static SpeedrunRecords.CategoryRecord category(String id, String name, String time) {
        return new SpeedrunRecords.CategoryRecord(id, name, "https://www.speedrun.com/celeste?x=" + id,
                new BigDecimal(time), "ingame",
                List.of(new SpeedrunRecords.Runner("Marlin", "https://www.speedrun.com/users/Marlin"),
                        new SpeedrunRecords.Runner("Guest", null)),
                LocalDate.of(2026, 10, 18), "https://www.speedrun.com/celeste/run/" + id, null);
    }

    private static SpeedrunRun pb(String categoryId, String category, int place, String time) {
        return new SpeedrunRun("o1y9j9v6", "Celeste", categoryId, category, place, new BigDecimal(time),
                LocalDate.of(2026, 9, 1), null, "https://www.speedrun.com/celeste/run/pb-" + categoryId);
    }

    private static final SpeedrunRun PENDING = new SpeedrunRun("o1y9j9v6", "Celeste", "any", "Any%", null,
            new BigDecimal("1600.0"), null, Instant.parse("2026-11-20T10:00:00Z"),
            "https://www.speedrun.com/celeste/run/new");

    private static final SpeedrunRecords CELESTE_RECORDS = new SpeedrunRecords(CELESTE, List.of(
            category("any", "Any%", "1484.083"), category("arb", "ARB", "1700"), category("100", "100%", "5000")));

    private static GameEnrichment withSpeedrun(SpeedrunEnrichment speedrun) {
        return new GameEnrichment(null, null, null, null, null, null, null, null, null, null, speedrun, null);
    }

    @Test
    void summaryCarriesWorldRecordAndPersonalBest() {
        SpeedrunEnrichment speedrun = new SpeedrunEnrichment(CELESTE_RECORDS, RUNNER,
                List.of(pb("arb", "ARB", 3, "1800"), pb("any", "Any%", 42, "1612.5")), List.of(PENDING));
        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, withSpeedrun(speedrun), LINKS,
                Optional.of("72"));

        JsonNode summary = MAPPER.readTree(MAPPER.writerWithView(GameInfoResponse.Summary.class)
                .writeValueAsString(response));

        assertThat(summary.path("worldRecord").path("category").asString()).isEqualTo("Any%");
        assertThat(summary.path("worldRecord").path("time").decimalValue()).isEqualByComparingTo("1484.083");
        assertThat(summary.path("worldRecord").path("runners").size()).isEqualTo(2);
        assertThat(summary.path("worldRecord").path("runners").get(0).asString()).isEqualTo("Marlin");
        assertThat(summary.path("personalBest").path("category").asString()).isEqualTo("Any%");
        assertThat(summary.path("personalBest").path("time").decimalValue()).isEqualByComparingTo("1612.5");
        assertThat(summary.path("personalBest").path("place").asInt()).isEqualTo(42);
        assertThat(summary.has("speedrun")).isFalse();

        JsonNode full = MAPPER.readTree(MAPPER.writeValueAsString(response)).path("speedrun");
        assertThat(full.path("gameId").asString()).isEqualTo("o1y9j9v6");
        assertThat(full.path("name").asString()).isEqualTo("Celeste");
        assertThat(full.path("url").asString()).isEqualTo("https://www.speedrun.com/celeste");
        assertThat(full.path("coverUrl").asString()).isEqualTo(CELESTE.coverUrl());
        assertThat(full.path("worldRecords").get(0).path("runners").get(0).path("name").asString())
                .isEqualTo("Marlin");
        assertThat(full.path("worldRecords").get(0).path("timing").asString()).isEqualTo("ingame");
        assertThat(full.path("account").path("name").asString()).isEqualTo("linkus7");
        assertThat(full.path("account").path("source").asString()).isEqualTo("api_key");
        assertThat(full.path("account").has("userId")).isFalse();
        assertThat(full.path("personalBests").get(1).path("place").asInt()).isEqualTo(42);
        assertThat(full.path("personalBests").get(1).path("runUrl").asString())
                .isEqualTo("https://www.speedrun.com/celeste/run/pb-any");
        assertThat(full.path("pendingRuns").get(0).path("submitted").asString()).isEqualTo("2026-11-20T10:00:00Z");
        assertThat(full.path("pendingRuns").get(0).has("place")).isFalse();
        assertThat(full.path("more").asString()).isEqualTo(BASE + "/api/games/speedrun/o1y9j9v6?version=3");
    }

    @Test
    void personalBestFallsBackToBestPlace() {
        SpeedrunEnrichment speedrun = new SpeedrunEnrichment(CELESTE_RECORDS, RUNNER,
                List.of(pb("arb", "ARB", 9, "1800"), pb("100", "100%", 3, "5100")), List.of());

        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, withSpeedrun(speedrun), LINKS,
                Optional.of("72"));

        assertThat(response.personalBest()).isEqualTo(
                new GameInfoV3Response.PersonalBest("100%", new BigDecimal("5100"), 3));
    }

    @Test
    void unidentifiedMemberHasRecordsButNullPersonalData() {
        SpeedrunEnrichment speedrun = new SpeedrunEnrichment(CELESTE_RECORDS, null, null, null);

        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, withSpeedrun(speedrun), LINKS,
                Optional.of("72"));

        assertThat(response.speedrun().worldRecords()).hasSize(3);
        assertThat(response.speedrun().account()).isNull();
        assertThat(response.speedrun().personalBests()).isNull();
        assertThat(response.speedrun().pendingRuns()).isNull();
        assertThat(response.personalBest()).isNull();
        assertThat(response.worldRecord().category()).isEqualTo("Any%");
        JsonNode full = MAPPER.readTree(MAPPER.writeValueAsString(response)).path("speedrun");
        assertThat(full.has("personalBests")).isTrue();
        assertThat(full.path("personalBests").isNull()).isTrue();

        // identified but without any PB on the game: an empty list, no summary PB
        SpeedrunEnrichment noPb = new SpeedrunEnrichment(CELESTE_RECORDS, RUNNER, List.of(), List.of());
        GameInfoV3Response none = GameInfoV3Response.of(BASE_V2, withSpeedrun(noPb), LINKS, Optional.of("72"));
        assertThat(none.speedrun().personalBests()).isEmpty();
        assertThat(none.personalBest()).isNull();
    }

    @Test
    void aggregateKeepsThreeCategories() {
        List<SpeedrunRecords.CategoryRecord> seven = IntStream.range(0, 7)
                .mapToObj(i -> category("c" + i, "Category " + i, "100" + i)).toList();
        SpeedrunEnrichment speedrun = new SpeedrunEnrichment(new SpeedrunRecords(CELESTE, seven), null, null, null);

        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, withSpeedrun(speedrun), LINKS,
                Optional.of("72"));

        assertThat(response.speedrun().worldRecords()).containsExactlyElementsOf(seven.subList(0, 3));
    }

    @Test
    void noSpeedrunGame_noObjectNorSummaryFields() {
        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, GameEnrichment.EMPTY, LINKS, Optional.of("72"));

        assertThat(response.speedrun()).isNull();
        assertThat(response.worldRecord()).isNull();
        assertThat(response.personalBest()).isNull();
        GameInfoV3Response noCategory = GameInfoV3Response.of(BASE_V2, withSpeedrun(new SpeedrunEnrichment(
                new SpeedrunRecords(CELESTE, List.of()), RUNNER, List.of(pb("any", "Any%", 1, "1")), null)),
                LINKS, Optional.of("72"));
        assertThat(noCategory.worldRecord()).isNull();
        assertThat(noCategory.personalBest().place()).isEqualTo(1);
        assertThat(GameInfoV3Response.notInGame().worldRecord()).isNull();
    }

    // ---------------------------------------------------------------- personal stats

    private static Achievement unlocked(String id, String at, String rarity) {
        return new Achievement(id, "Name " + id, "d", "icon", true, Instant.parse(at), new BigDecimal(rarity), null,
                false);
    }

    private static final PlatformStats STEAM = new PlatformStats(Status.OK, "620", 7320L, 95L,
            Instant.parse("2026-11-21T20:14:00Z"), AchievementSummary.of(List.of(
                    unlocked("S1", "2026-11-01T10:00:00Z", "12.5"),
                    new Achievement("S2", "n", "d", "i", false, null, null, null, false))),
            null, Map.of());
    private static final PlatformStats XBOX = new PlatformStats(Status.OK, "1144039928", 610L, null,
            Instant.parse("2026-11-20T20:00:00Z"), AchievementSummary.of(List.of(
                    unlocked("X1", "2026-11-10T10:00:00Z", "3.2"),
                    unlocked("X2", "2026-11-12T10:00:00Z", "40"),
                    new Achievement("X3", null, null, "i", false, null, null, 10, true))),
            null, Map.of("gamerscore", 350, "gamerscoreTotal", 1000));
    private static final PlatformStats RA = new PlatformStats(Status.OK, "228", null, null, null,
            AchievementSummary.of(List.of(unlocked("1", "2026-11-15T10:00:00Z", "25.00"))), null,
            Map.of("title", "Super Mario World", "console", "SNES", "playtime", 120, "points", 15,
                    "pointsTotal", 40, "hardcoreUnlocked", 1, "hardcorePoints", 5, "award", "beaten-hardcore"));

    private static GameInfoV3Response withStats(PlatformStats steam, PlatformStats xbox, PlatformStats ra,
                                                GameBinding.SourceType detectedOn) {
        GameEnrichment enrichment = new GameEnrichment(null, null, null, null, null, null, null, null, null, null,
                null, PersonalStatsEnrichment.of(steam, xbox, ra, detectedOn));
        return GameInfoV3Response.of(BASE_V2, enrichment, LINKS, Optional.of("72"));
    }

    @Test
    void statsObjectsCarryTheirMoreLinks() {
        JsonNode stats = MAPPER.readTree(MAPPER.writeValueAsString(withStats(STEAM, XBOX, RA,
                GameBinding.SourceType.STEAM))).path("stats");

        assertThat(stats.path("steam").path("status").asString()).isEqualTo("ok");
        assertThat(stats.path("steam").path("appId").asString()).isEqualTo("620");
        assertThat(stats.path("steam").path("playtimeMinutes").asLong()).isEqualTo(7320);
        assertThat(stats.path("steam").path("achievements").path("unlocked").asInt()).isEqualTo(1);
        assertThat(stats.path("steam").path("more").asString())
                .isEqualTo(BASE + "/api/me/stats/steam/620?version=3");
        assertThat(stats.path("xbox").path("gamerscore").path("earned").asInt()).isEqualTo(350);
        assertThat(stats.path("xbox").path("gamerscore").path("total").asInt()).isEqualTo(1000);
        assertThat(stats.path("xbox").path("more").asString())
                .isEqualTo(BASE + "/api/me/stats/xbox/1144039928?version=3");
        JsonNode ra = stats.path("retroachievements");
        assertThat(ra.path("gameId").asInt()).isEqualTo(228);
        assertThat(ra.path("title").asString()).isEqualTo("Super Mario World");
        assertThat(ra.path("console").asString()).isEqualTo("SNES");
        assertThat(ra.path("playtime").asInt()).isEqualTo(120);
        assertThat(ra.path("points").path("earned").asInt()).isEqualTo(15);
        assertThat(ra.path("points").path("total").asInt()).isEqualTo(40);
        assertThat(ra.path("hardcore").path("unlocked").asInt()).isEqualTo(1);
        assertThat(ra.path("hardcore").path("points").asInt()).isEqualTo(5);
        assertThat(ra.path("award").asString()).isEqualTo("beaten-hardcore");
        assertThat(ra.has("playtimeMinutes")).isFalse();
        assertThat(ra.path("more").asString())
                .isEqualTo(BASE + "/api/me/stats/retroachievements/228?version=3");
        assertThat(stats.has("detectedOn")).isFalse();
    }

    @Test
    void unavailableWithoutIdHasNoMoreLink() {
        GameInfoV3Response response = withStats(null, PlatformStats.of(Status.UNAVAILABLE, null), null,
                GameBinding.SourceType.STEAM);

        assertThat(response.stats().xbox().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(response.stats().xbox().more()).isNull();
        assertThat(response.stats().steam()).isNull();
    }

    @Test
    void summaryUsesTheDetectedPlatform() {
        GameInfoV3Response response = withStats(STEAM, XBOX, RA, GameBinding.SourceType.XBOX);

        assertThat(response.playtime()).isEqualTo(610L);
        assertThat(response.achievements())
                .isEqualTo(new GameInfoV3Response.AchievementsSummary("xbox", 2, 3));
        JsonNode summary = MAPPER.readTree(MAPPER.writerWithView(GameInfoResponse.Summary.class)
                .writeValueAsString(response));
        assertThat(summary.path("playtime").asLong()).isEqualTo(610);
        assertThat(summary.path("achievements").path("platform").asString()).isEqualTo("xbox");
        assertThat(summary.path("achievements").path("unlocked").asInt()).isEqualTo(2);
        assertThat(summary.path("achievements").path("total").asInt()).isEqualTo(3);
        assertThat(summary.has("stats")).isFalse();
    }

    @Test
    void summaryFallsBackToTheFirstOkPlatform() {
        GameInfoV3Response response = withStats(PlatformStats.of(Status.NOT_OWNED, "620"), null, RA,
                GameBinding.SourceType.MANUAL);

        // RetroAchievements' play time is not in minutes: never the summary's
        assertThat(response.playtime()).isNull();
        assertThat(response.achievements())
                .isEqualTo(new GameInfoV3Response.AchievementsSummary("retroachievements", 1, 1));
    }

    @Test
    void anUnavailableDetectedPlatformFallsBackToTheFirstOkOne() {
        GameInfoV3Response response = withStats(PlatformStats.of(Status.UNAVAILABLE, "620"), XBOX, RA,
                GameBinding.SourceType.STEAM);

        assertThat(response.playtime()).isEqualTo(610L);
        assertThat(response.achievements())
                .isEqualTo(new GameInfoV3Response.AchievementsSummary("xbox", 2, 3));
    }

    @Test
    void lastAchievementIsTheLatestAcrossPlatforms() {
        GameInfoV3Response response = withStats(STEAM, XBOX, RA, GameBinding.SourceType.STEAM);

        assertThat(response.lastAchievement()).isEqualTo(new GameInfoV3Response.LastAchievement("retroachievements",
                "Name 1", Instant.parse("2026-11-15T10:00:00Z"), new BigDecimal("25.00")));
        JsonNode summary = MAPPER.readTree(MAPPER.writerWithView(GameInfoResponse.Summary.class)
                .writeValueAsString(response));
        assertThat(summary.path("lastAchievement").path("platform").asString()).isEqualTo("retroachievements");
        assertThat(summary.path("lastAchievement").path("unlockedAt").asString()).isEqualTo("2026-11-15T10:00:00Z");
    }

    @Test
    void privateSteamGivesNoSummaryNumbers() {
        GameInfoV3Response detected = withStats(PlatformStats.of(Status.PRIVATE, "620"), null, null,
                GameBinding.SourceType.STEAM);
        GameInfoV3Response elsewhere = withStats(PlatformStats.of(Status.PRIVATE, "620"), null, null,
                GameBinding.SourceType.MANUAL);

        for (GameInfoV3Response response : List.of(detected, elsewhere)) {
            assertThat(response.playtime()).isNull();
            assertThat(response.achievements()).isNull();
            assertThat(response.lastAchievement()).isNull();
        }
    }

    @Test
    void noStats_noObjectNorSummaryFields() {
        GameInfoV3Response response = GameInfoV3Response.of(BASE_V2, GameEnrichment.EMPTY, LINKS, Optional.of("72"));

        assertThat(response.stats()).isNull();
        assertThat(response.playtime()).isNull();
        assertThat(response.achievements()).isNull();
        assertThat(response.lastAchievement()).isNull();
        assertThat(GameInfoV3Response.notInGame().stats()).isNull();
    }
}
