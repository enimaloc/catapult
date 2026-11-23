package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardAppTokenService;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardClient;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardNotFoundException;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardRateLimiter;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardRegion;
import fr.enimaloc.catapult.service.enrichment.blizzard.HttpBlizzardClient;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardUnavailableException;
import fr.enimaloc.catapult.service.enrichment.opencritic.HttpOpenCriticClient;
import fr.enimaloc.catapult.service.enrichment.riot.HttpRiotClient;
import fr.enimaloc.catapult.service.enrichment.riot.RiotClient;
import fr.enimaloc.catapult.service.enrichment.riot.RiotNotFoundException;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitClient;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitNotFoundException;
import fr.enimaloc.catapult.service.enrichment.faceit.HttpFaceitClient;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticClient;
import fr.enimaloc.catapult.service.enrichment.pcgw.HttpPcGamingWikiClient;
import fr.enimaloc.catapult.service.enrichment.prices.HttpItadClient;
import fr.enimaloc.catapult.service.enrichment.prices.ItadClient;
import fr.enimaloc.catapult.service.enrichment.speedrun.HttpSpeedrunClient;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunClient;
import fr.enimaloc.catapult.service.enrichment.twitch.HelixTwitchActivityClient;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivityClient;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiClient;
import fr.enimaloc.catapult.service.enrichment.protondb.HttpProtonDbClient;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbClient;
import fr.enimaloc.catapult.service.enrichment.steamdeck.HttpSteamDeckClient;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckClient;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.HttpSteamGridDbClient;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbClient;
import fr.enimaloc.catapult.service.enrichment.rastats.RaCredentials;
import fr.enimaloc.catapult.service.enrichment.rastats.RaKeyRejectedException;
import fr.enimaloc.catapult.service.enrichment.rastats.RetroAchievementsClient;
import fr.enimaloc.catapult.service.enrichment.steamstats.HttpSteamStatsClient;
import fr.enimaloc.catapult.service.enrichment.steamstats.SteamStatsClient;
import fr.enimaloc.catapult.service.enrichment.xboxstats.HttpXboxStatsClient;
import fr.enimaloc.catapult.service.enrichment.xboxstats.XboxStatsClient;
import fr.enimaloc.catapult.service.enrichment.xboxstats.XboxStatsForbiddenException;
import fr.enimaloc.catapult.service.enrichment.rastats.HttpRetroAchievementsClient;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService.XstsSession;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Which enrichment client a context resolves: the simulated one (primary) when the simulation is on, the HTTP one
 * otherwise. The HTTP clients are mocks and the simulated ones are scanned with their real conditions: only the
 * selection is under test.
 */
class SimulatedEnrichmentClientsWiringTest {

    @Configuration(proxyBeanMethods = false)
    static class HttpClients {
        @Bean HttpProtonDbClient protonDb() { return mock(HttpProtonDbClient.class); }
        @Bean HttpSteamDeckClient steamDeck() { return mock(HttpSteamDeckClient.class); }
        @Bean HttpPcGamingWikiClient pcgw() { return mock(HttpPcGamingWikiClient.class); }
        @Bean HttpSteamGridDbClient steamGridDb() { return mock(HttpSteamGridDbClient.class); }
        @Bean HttpItadClient itad() { return mock(HttpItadClient.class); }
        @Bean HttpOpenCriticClient openCritic() { return mock(HttpOpenCriticClient.class); }
        @Bean HttpSpeedrunClient speedrun() { return mock(HttpSpeedrunClient.class); }
        @Bean HelixTwitchActivityClient twitchActivity() { return mock(HelixTwitchActivityClient.class); }
        @Bean HttpSteamStatsClient steamStats() { return mock(HttpSteamStatsClient.class); }
        @Bean HttpXboxStatsClient xboxStats() { return mock(HttpXboxStatsClient.class); }
        @Bean HttpRetroAchievementsClient raStats() { return mock(HttpRetroAchievementsClient.class); }
        @Bean HttpBlizzardClient blizzard() { return mock(HttpBlizzardClient.class); }
        @Bean fr.enimaloc.catapult.service.enrichment.osu.HttpOsuClient osu() {
            return mock(fr.enimaloc.catapult.service.enrichment.osu.HttpOsuClient.class);
        }
        @Bean fr.enimaloc.catapult.service.enrichment.bungie.HttpBungieClient bungie() {
            return mock(fr.enimaloc.catapult.service.enrichment.bungie.HttpBungieClient.class);
        }
        @Bean HttpRiotClient riot() { return mock(HttpRiotClient.class); }
        @Bean HttpFaceitClient faceit() { return mock(HttpFaceitClient.class); }
        @Bean org.springframework.web.client.RestClient.Builder restClientBuilder() {
            return org.springframework.web.client.RestClient.builder();
        }
        @Bean BlizzardRateLimiter blizzardLimiter() { return mock(BlizzardRateLimiter.class); }
        @Bean BlizzardAppTokenService blizzardTokens() { return mock(BlizzardAppTokenService.class); }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = SimulatedProtonDbClient.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = ".*Simulated(ProtonDb|SteamDeck|PcGamingWiki|SteamGridDb|Itad|OpenCritic|Speedrun|TwitchActivity|SteamStats|XboxStats|RetroAchievements|Blizzard|Bungie|Riot|Faceit|Osu)Client|.*SimulatedBlizzardAppTokenService"))
    static class SimulatedClients {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HttpClients.class, SimulatedClients.class);

    @Test
    void simulationOn_selectsTheSimulatedClients() {
        runner.withPropertyValues("catapult.simulation.enabled=true").run(ctx -> {
            assertThat(ctx.getBean(ProtonDbClient.class)).isInstanceOf(SimulatedProtonDbClient.class);
            assertThat(ctx.getBean(SteamDeckClient.class)).isInstanceOf(SimulatedSteamDeckClient.class);
            assertThat(ctx.getBean(PcGamingWikiClient.class)).isInstanceOf(SimulatedPcGamingWikiClient.class);
            assertThat(ctx.getBean(SteamGridDbClient.class)).isInstanceOf(SimulatedSteamGridDbClient.class);
            assertThat(ctx.getBean(ItadClient.class)).isInstanceOf(SimulatedItadClient.class);
            assertThat(ctx.getBean(OpenCriticClient.class)).isInstanceOf(SimulatedOpenCriticClient.class);
            assertThat(ctx.getBean(SpeedrunClient.class)).isInstanceOf(SimulatedSpeedrunClient.class);
            assertThat(ctx.getBean(TwitchActivityClient.class)).isInstanceOf(SimulatedTwitchActivityClient.class);
            assertThat(ctx.getBean(SteamStatsClient.class)).isInstanceOf(SimulatedSteamStatsClient.class);
            assertThat(ctx.getBean(XboxStatsClient.class)).isInstanceOf(SimulatedXboxStatsClient.class);
            assertThat(ctx.getBean(RetroAchievementsClient.class))
                    .isInstanceOf(SimulatedRetroAchievementsClient.class);
            assertThat(ctx.getBean(BlizzardClient.class)).isInstanceOf(SimulatedBlizzardClient.class);
            assertThat(ctx.getBean(RiotClient.class)).isInstanceOf(SimulatedRiotClient.class);
            assertThat(ctx.getBean(FaceitClient.class)).isInstanceOf(SimulatedFaceitClient.class);
            assertThat(ctx.getBean(BlizzardAppTokenService.class))
                    .isInstanceOf(SimulatedBlizzardAppTokenService.class);
            assertThat(ctx.getBean(fr.enimaloc.catapult.service.enrichment.bungie.BungieClient.class))
                    .isInstanceOf(SimulatedBungieClient.class);
            assertThat(ctx.getBean(fr.enimaloc.catapult.service.enrichment.osu.OsuClient.class))
                    .isInstanceOf(SimulatedOsuClient.class);
        });
    }

    @Test
    void simulationOff_keepsTheHttpClients() {
        runner.run(ctx -> {
            assertThat(ctx.getBean(ProtonDbClient.class)).isInstanceOf(HttpProtonDbClient.class);
            assertThat(ctx.getBean(SteamDeckClient.class)).isInstanceOf(HttpSteamDeckClient.class);
            assertThat(ctx.getBean(PcGamingWikiClient.class)).isInstanceOf(HttpPcGamingWikiClient.class);
            assertThat(ctx.getBean(SteamGridDbClient.class)).isInstanceOf(HttpSteamGridDbClient.class);
            assertThat(ctx.getBean(ItadClient.class)).isInstanceOf(HttpItadClient.class);
            assertThat(ctx.getBean(OpenCriticClient.class)).isInstanceOf(HttpOpenCriticClient.class);
            assertThat(ctx.getBean(SpeedrunClient.class)).isInstanceOf(HttpSpeedrunClient.class);
            assertThat(ctx.getBean(TwitchActivityClient.class)).isInstanceOf(HelixTwitchActivityClient.class);
            assertThat(ctx.getBean(SteamStatsClient.class)).isInstanceOf(HttpSteamStatsClient.class);
            assertThat(ctx.getBean(XboxStatsClient.class)).isInstanceOf(HttpXboxStatsClient.class);
            assertThat(ctx.getBean(RetroAchievementsClient.class)).isInstanceOf(HttpRetroAchievementsClient.class);
            assertThat(ctx.getBean(BlizzardClient.class)).isInstanceOf(HttpBlizzardClient.class);
            assertThat(ctx.getBean(fr.enimaloc.catapult.service.enrichment.bungie.BungieClient.class))
                    .isInstanceOf(fr.enimaloc.catapult.service.enrichment.bungie.HttpBungieClient.class);
            assertThat(ctx.getBean(RiotClient.class)).isInstanceOf(HttpRiotClient.class);
            assertThat(ctx.getBean(FaceitClient.class)).isInstanceOf(HttpFaceitClient.class);
            assertThat(ctx.getBean(fr.enimaloc.catapult.service.enrichment.osu.OsuClient.class))
                    .isInstanceOf(fr.enimaloc.catapult.service.enrichment.osu.HttpOsuClient.class);
        });
    }

    @Test
    void simulatedAnswers_needNoNetworkNorKey() {
        assertThat(new SimulatedProtonDbClient().fetchSummary("570")).isPresent();
        assertThat(new SimulatedSteamDeckClient().fetch("570")).isPresent();
        assertThat(new SimulatedPcGamingWikiClient().findByName("Dota 2")).isPresent();
        assertThat(new SimulatedSteamGridDbClient().fetchBySteamAppId("570")).get()
                .satisfies(a -> assertThat(a.bestGrid()).isNotNull());
    }

    @Test
    void simulatedNewSources_answerDeterministically() {
        var itad = new SimulatedItadClient();
        assertThat(itad.isConfigured()).isTrue();
        var game = itad.lookupBySteamAppId("570").orElseThrow();
        assertThat(itad.lookupByTitle("Dota 2")).isPresent();
        assertThat(itad.info(game.id())).isPresent();
        assertThat(itad.prices(game, "FR").orElseThrow()).satisfies(p -> {
            assertThat(p.currency()).isEqualTo("EUR");
            assertThat(p.deals()).hasSize(3);
            assertThat(p.best()).isEqualTo(p.deals().getFirst());
        });

        var oc = new SimulatedOpenCriticClient();
        assertThat(oc.isConfigured()).isTrue();
        var hit = oc.search("Dota 2").getFirst();
        assertThat(hit.name()).isEqualTo("Dota 2");
        assertThat(oc.game(hit.id()).orElseThrow().topCriticScore()).isEqualTo(88);

        var twitch = new SimulatedTwitchActivityClient();
        assertThat(twitch.isConfigured()).isTrue();
        var category = twitch.categoryByIgdbId("1").orElseThrow();
        assertThat(twitch.categoryById(category.id())).isPresent();
        assertThat(twitch.lives(category.id(), null).liveCount()).isEqualTo(42);
        assertThat(twitch.clips(category.id(), java.time.Instant.now()).top()).isNotEmpty();
    }

    @Test
    void simulatedSpeedrun_answersDeterministically() {
        var sr = new SimulatedSpeedrunClient();
        assertThat(sr.searchGames("Celeste").getFirst().name()).isEqualTo("Celeste");
        var records = sr.records("any").orElseThrow();
        assertThat(records.categories()).hasSize(3);
        assertThat(records.categories().getFirst().runners()).hasSize(2);
        var user = sr.usersByTwitch("simulated").getFirst();
        assertThat(sr.user("whoever")).contains(user);
        assertThat(sr.profile("valid")).contains(user);
        assertThat(sr.personalBests(user.id(), null)).hasSize(2).allSatisfy(r -> assertThat(r.place()).isNotNull());
        assertThat(sr.pendingRuns(user.id(), null, null)).hasSize(1)
                .allSatisfy(r -> assertThat(r.place()).isNull());
    }

    @Test
    void simulatedSteamStats_answerDeterministically() {
        var steam = new SimulatedSteamStatsClient();
        assertThat(steam.library("76561198000000000", null).orElseThrow().games().get("440").playtimeMinutes())
                .isEqualTo(7_320);
        assertThat(steam.library("private", null)).isEmpty();
        assertThat(steam.playerAchievements("1", "440", "en", null).unlocked()).hasSize(12);
        assertThat(steam.schema("440", "en").orElseThrow().achievements()).hasSize(50);
        assertThat(steam.globalPercentages("440").get("ACH_1")).isEqualByComparingTo("0.4");
        assertThat(steam.schema("1", "en")).isEmpty();
    }

    @Test
    void simulatedXboxStats_answerDeterministically() {
        var xbox = new SimulatedXboxStatsClient();
        var session = new XstsSession("t", "h", "x");
        var id = java.util.UUID.randomUUID();
        var title = xbox.titleHistory(id, session, 10, java.util.Locale.US).getFirst();
        assertThat(title.productIds()).containsExactly("9NBLGGH4NNS1");
        assertThat(title.currentGamerscore()).isEqualTo(350);
        assertThat(title.totalGamerscore()).isEqualTo(1000);
        var achievements = xbox.achievements(id, session, title.titleId(), java.util.Locale.US);
        assertThat(achievements).hasSize(10).filteredOn(a -> a.unlocked()).hasSize(3);
        assertThat(achievements.stream().filter(a -> a.unlocked()).mapToInt(a -> a.points()).sum())
                .as("the unlocked gamerscore matches the title history").isEqualTo(title.currentGamerscore());
        assertThat(achievements.stream().mapToInt(a -> a.points()).sum()).isEqualTo(title.totalGamerscore());
        assertThat(xbox.minutesPlayed(id, session, title.titleId())).contains(480L);
        assertThatThrownBy(() -> xbox.achievements(id, session, "2000002", java.util.Locale.US))
                .isInstanceOf(XboxStatsForbiddenException.class);
    }

    @Test
    void simulatedRetroAchievements_acceptsOnlyTheValidKey() {
        var now = java.time.Instant.parse("2026-11-22T10:00:00Z");
        var ra = new SimulatedRetroAchievementsClient(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        var key = new RaCredentials("valid", true);
        var user = ra.profile("simulated", key).orElseThrow();
        assertThat(ra.profile(user.ulid(), key)).isPresent();
        assertThat(ra.profile("nobody", key)).isEmpty();
        var recent = ra.recentlyPlayed(user.ulid(), 1, key).getFirst();
        assertThat(recent.title()).isEqualTo("Super Mario World");
        assertThat(recent.lastPlayed()).isEqualTo(now.minusSeconds(120));
        var progress = ra.progress(recent.gameId(), user.ulid(), key).orElseThrow();
        assertThat(progress.numAchievements()).isEqualTo(40);
        assertThat(progress.numAwardedToUser()).isEqualTo(10);
        assertThat(progress.numAwardedToUserHardcore()).isEqualTo(8);
        assertThat(progress.achievements()).filteredOn(a -> a.dateEarnedHardcore() != null).hasSize(8);
        assertThat(ra.recentAchievements(user.ulid(), 60, key)).isEmpty();
        assertThatThrownBy(() -> ra.profile("simulated", new RaCredentials("other", false)))
                .isInstanceOf(RaKeyRejectedException.class);
        assertThat(new SimulatedRetroAchievementsClient()).isNotNull();
    }

    @Test
    void simulatedOsu_answersDeterministically() {
        var osu = new SimulatedOsuClient();
        assertThat(osu.refresh("any").refreshToken()).isNotBlank();
        assertThat(osu.me("t").id()).isEqualTo(SimulatedOsuClient.USER_ID);
        assertThat(osu.me("t").statistics().pp()).isPositive();
        assertThat(osu.recentScores(SimulatedOsuClient.USER_ID, "osu", 5, "t")).hasSize(2);
        assertThat(osu.recentScores(SimulatedOsuClient.USER_ID, "osu", 1, "t")).hasSize(1);
    }

    @Test
    void simulatedBungie_answersDeterministically() {
        var bungie = new SimulatedBungieClient();
        assertThat(bungie.refresh("any").membershipId()).isEqualTo(SimulatedBungieClient.MEMBERSHIP_ID);
        assertThat(bungie.currentUser("t").destiny().membershipId()).isEqualTo(SimulatedBungieClient.DESTINY_ID);
        assertThat(bungie.profile(3, SimulatedBungieClient.DESTINY_ID, "t").characters()).hasSize(2);
        assertThatThrownBy(() -> bungie.profile(3, "0", "t"))
                .isInstanceOf(fr.enimaloc.catapult.service.enrichment.bungie.BungieExceptions.NotFound.class);
        assertThat(bungie.triumphScore(3, SimulatedBungieClient.DESTINY_ID, "t")).isPositive();
        assertThat(bungie.definition("DestinyActivityModeDefinition", 2_222L, "en").name()).isEqualTo("Vanguard Ops");
        assertThat(bungie.definition("DestinyActivityDefinition", 1_111L, "en").name()).isNotBlank();
        assertThat(bungie.season(bungie.currentSeasonHash(), "en").number()).isEqualTo(28);
    }

    @Test
    void simulatedBlizzard_answersDeterministically() {
        var bz = new SimulatedBlizzardClient();
        var mage = bz.wowCharacter(BlizzardRegion.EU, "realm", "SimulatedMage", "en_US");
        assertThat(mage.level()).isEqualTo(90);
        assertThat(mage.achievementPoints()).isEqualTo(28_755);
        assertThat(mage.mythicRating()).isEqualTo(2_410.0);
        assertThat(bz.wowCharacter(BlizzardRegion.EU, "realm", "alt", "en_US").level()).isEqualTo(70);
        assertThatThrownBy(() -> bz.wowCharacter(BlizzardRegion.EU, "realm", "gone", "en_US"))
                .isInstanceOf(BlizzardNotFoundException.class);
        assertThat(bz.wowAchievements(BlizzardRegion.EU, "realm", "alt", "en_US").recent()).isNotEmpty();
        assertThat(bz.wowUserCharacters(BlizzardRegion.EU, "t", "en_US")).hasSize(2);
        var ref = bz.sc2Profiles(bz.userAccountId("t")).getFirst();
        assertThat(ref.regionId()).isEqualTo(2);
        var sc2 = bz.sc2Profile(BlizzardRegion.EU, ref, "en_US", true);
        assertThat(sc2.modes().get("1v1")).satisfies(m -> {
            assertThat(m.leagueName()).isEqualTo("Diamond");
            assertThat(m.rank()).isEqualTo(12);
            assertThat(m.totalGames()).isEqualTo(40);
            assertThat(m.totalWins()).isEqualTo(25);
        });
        assertThat(bz.sc2Profile(BlizzardRegion.EU, ref, "en_US", false).ladder()).isEmpty();
        var d3 = bz.d3Profile(BlizzardRegion.EU, "Sim#1234", "en_US");
        assertThat(d3.paragonLevel()).isEqualTo(1_633);
        assertThat(d3.paragonLevelSeason()).isEqualTo(900);
        assertThat(d3.heroes().getFirst()).satisfies(h -> {
            assertThat(h.className()).isEqualTo("barbarian");
            assertThat(h.level()).isEqualTo(70);
            assertThat(h.seasonal()).isTrue();
        });
        assertThat(bz.wowToken(BlizzardRegion.EU).gold()).isEqualTo(470_531L);
        assertThat(bz.hearthstoneSets("en_US")).hasSize(1);
        assertThat(bz.hearthstoneDeck(SimulatedBlizzardClient.DECK_CODE, "en_US").cards()).isNotEmpty();
        assertThatThrownBy(() -> bz.hearthstoneDeck("AAAA", "en_US"))
                .isInstanceOf(BlizzardUnavailableException.class);
        assertThat(bz.d3CurrentSeason(BlizzardRegion.EU)).isPositive();
        assertThat(java.util.Base64.getDecoder().decode(SimulatedBlizzardClient.DECK_CODE)).isNotEmpty();
    }

    @Test
    void simulatedRiot_answersDeterministically() {
        var riot = new SimulatedRiotClient();
        var account = riot.accountByRiotId(RiotPlatform.Cluster.EUROPE, "Catapult", "EUW");
        assertThat(account.puuid()).isEqualTo("sim-catapult-euw");
        assertThatThrownBy(() -> riot.accountByRiotId(RiotPlatform.Cluster.EUROPE, "Ghost", "0000"))
                .isInstanceOf(RiotNotFoundException.class);
        assertThat(riot.lolSummoner(RiotPlatform.EUW1, "p", "k").level()).isEqualTo(312);
        assertThat(riot.lolLeague(RiotPlatform.EUW1, "p", "k")).hasSize(2);
        assertThat(riot.lolActiveGame(RiotPlatform.EUW1, "p", "k")).hasValueSatisfying(g ->
                assertThat(g.championId()).isEqualTo(266));
        assertThat(riot.tftLeague(RiotPlatform.EUW1, "p", "k").getFirst().tier()).isEqualTo("PLATINUM");
        assertThat(riot.champions(riot.latestVersion(), "en_US").byKey()).containsKey("266");
    }

    @Test
    void simulatedFaceit_answersDeterministically() {
        var faceit = new SimulatedFaceitClient();
        var player = faceit.playerByNickname("catapult");
        assertThat(player.playerId()).isEqualTo(SimulatedFaceitClient.PLAYER_ID);
        assertThat(player.cs2().elo()).isEqualTo(1850);
        assertThat(faceit.player(SimulatedFaceitClient.PLAYER_ID).nickname()).isEqualTo("Catapult");
        assertThatThrownBy(() -> faceit.playerByNickname("Ghost")).isInstanceOf(FaceitNotFoundException.class);
        assertThatThrownBy(() -> faceit.player("nope")).isInstanceOf(FaceitNotFoundException.class);
        assertThat(faceit.lifetime(SimulatedFaceitClient.PLAYER_ID, "cs2").matches()).isEqualTo(420);
        assertThat(faceit.lastMatch(SimulatedFaceitClient.PLAYER_ID, "cs2")).hasValueSatisfying(m ->
                assertThat(m.map()).isEqualTo("de_mirage"));
    }
}
