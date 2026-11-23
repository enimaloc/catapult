package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.service.account.ApiTokenOwner;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.MemberFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStats;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStatsService;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BlizzardGame;
import fr.enimaloc.catapult.service.enrichment.rastats.RaAccountView;
import fr.enimaloc.catapult.service.enrichment.rastats.RetroAchievementsStatsService;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.AchievementSummary;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.enrichment.steamstats.SteamStatsService;
import fr.enimaloc.catapult.service.enrichment.xboxstats.XboxStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = MeStatsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, MeStatsControllerTest.Keys.class, ApiUserResolver.class, PublicBaseUrl.class})
@ActiveProfiles("test")
class MeStatsControllerTest {

    private static final String STATS = "/api/me/stats";
    private static final String TOKEN = "catr_" + "t".repeat(43);
    private static final PlatformStats STEAM = new PlatformStats(Status.OK, "440", 7320L, 95L, null,
            AchievementSummary.of(List.of(new Achievement("A1", "First", "d", "i", true,
                    Instant.parse("2026-11-20T10:00:00Z"), new BigDecimal("3.2"), null, false))),
            List.of(new Achievement("A1", "First", "d", "i", true, Instant.parse("2026-11-20T10:00:00Z"),
                    new BigDecimal("3.2"), null, false)), Map.of());

    @Autowired MockMvc mvc;
    @MockitoBean UserApiTokenService tokens;
    @MockitoBean UserAccountRepository accounts;
    @MockitoBean GameInfoAssembler gameInfoAssembler;
    @MockitoBean SteamStatsService steamStats;
    @MockitoBean XboxStatsService xboxStats;
    @MockitoBean RetroAchievementsStatsService raStats;
    @MockitoBean MemberFetchBudgets budgets;
    @MockitoBean BlizzardStatsService blizzardStats;
    @MockitoBean fr.enimaloc.catapult.service.enrichment.bungie.BungieStatsService bungieStats;
    @MockitoBean fr.enimaloc.catapult.service.enrichment.riot.RiotStatsService riotStats;
    @MockitoBean fr.enimaloc.catapult.service.enrichment.faceit.FaceitStatsService faceitStats;
    @MockitoBean fr.enimaloc.catapult.service.enrichment.chess.ChessStatsService chessStats;
    @MockitoBean fr.enimaloc.catapult.service.enrichment.osu.OsuStatsService osuStats;
    @MockitoBean fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStatsService hypixelStats;
    @MockitoBean fr.enimaloc.catapult.service.enrichment.supercell.SupercellStatsService supercellStats;

    private UserAccount user;
    private boolean budgetLeft = true;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        when(tokens.authenticate(TOKEN)).thenReturn(Optional.of(new ApiTokenOwner(user, ApiTokenScope.READ)));
        when(budgets.forMember(user.getId())).thenReturn(new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return budgetLeft;
            }

            @Override
            public Duration retryAfter() {
                return Duration.ofSeconds(42);
            }
        });
    }

    private static MockHttpServletRequestBuilder v3(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TOKEN).header("X-API-Version", "3");
    }

    @Test
    void currentGameStats() throws Exception {
        GameInfoV3Response.StatsObject stats = new GameInfoV3Response.StatsObject(
                new GameInfoV3Response.SteamStats(Status.OK, "440", 7320L, 95L, null, null, null,
                        "http://localhost/api/me/stats/steam/440?version=3"), null, null, null, null);
        when(gameInfoAssembler.currentStats(user, Locale.FRENCH))
                .thenReturn(new MeStatsController.CurrentStats(true, stats));

        mvc.perform(v3(get(STATS)).param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGame").value(true))
                .andExpect(jsonPath("$.stats.steam.playtimeMinutes").value(7320))
                .andExpect(jsonPath("$.stats.steam.more").value("http://localhost/api/me/stats/steam/440?version=3"))
                .andExpect(jsonPath("$.stats.xbox").doesNotExist())
                .andExpect(jsonPath("$.stats.steam.all").doesNotExist());
    }

    @Test
    void steamDetail_everyAchievement() throws Exception {
        when(steamStats.forGame(eq(user), eq("440"), eq(Locale.ENGLISH), any(), eq(true)))
                .thenReturn(Optional.of(STEAM));

        mvc.perform(v3(get(STATS + "/steam/440")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.appId").value("440"))
                .andExpect(jsonPath("$.playtimeMinutes").value(7320))
                .andExpect(jsonPath("$.achievements.unlocked").value(1))
                .andExpect(jsonPath("$.all[0].name").value("First"))
                .andExpect(jsonPath("$.all[0].rarity").value(3.2))
                .andExpect(jsonPath("$.more").value("http://localhost/api/me/stats/steam/440?version=3"))
                // the aggregate's shape, never the internal one
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.extra").doesNotExist());
    }

    @Test
    void xboxAndRetroAchievementsDetails_areTheAggregateObjectsWithEveryAchievement() throws Exception {
        List<Achievement> all = List.of(new Achievement("1", "One", "d", "i", true,
                Instant.parse("2026-11-20T10:00:00Z"), null, 50, false));
        when(xboxStats.forTitle(eq(user), eq("1144039928"), any(), any(), eq(true))).thenReturn(Optional.of(
                new PlatformStats(Status.OK, "1144039928", 480L, null, null, AchievementSummary.of(all), all,
                        Map.of("gamerscore", 50, "gamerscoreTotal", 1000))));
        when(raStats.forGame(eq(user), eq(228), any(), eq(true))).thenReturn(Optional.of(
                new PlatformStats(Status.OK, "228", null, null, null, AchievementSummary.of(all), all,
                        Map.of(RetroAchievementsStatsService.TITLE, "Super Mario World",
                                RetroAchievementsStatsService.POINTS, 5,
                                RetroAchievementsStatsService.POINTS_TOTAL, 40))));

        mvc.perform(v3(get(STATS + "/xbox/1144039928")).header("Accept-Language", "fr-FR,fr;q=0.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titleId").value("1144039928"))
                .andExpect(jsonPath("$.gamerscore.earned").value(50))
                .andExpect(jsonPath("$.gamerscore.total").value(1000))
                .andExpect(jsonPath("$.all[0].name").value("One"))
                .andExpect(jsonPath("$.more").value("http://localhost/api/me/stats/xbox/1144039928?version=3"))
                .andExpect(jsonPath("$.extra").doesNotExist());
        verify(xboxStats).forTitle(eq(user), eq("1144039928"), eq(Locale.FRANCE), any(), eq(true));
        mvc.perform(v3(get(STATS + "/retroachievements/228")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(228))
                .andExpect(jsonPath("$.title").value("Super Mario World"))
                .andExpect(jsonPath("$.points.earned").value(5))
                .andExpect(jsonPath("$.points.total").value(40))
                .andExpect(jsonPath("$.all[0].name").value("One"))
                .andExpect(jsonPath("$.more")
                        .value("http://localhost/api/me/stats/retroachievements/228?version=3"))
                .andExpect(jsonPath("$.extra").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void privateIs200WithStatus() throws Exception {
        when(steamStats.forGame(eq(user), eq("440"), any(), any(), eq(true)))
                .thenReturn(Optional.of(PlatformStats.of(Status.PRIVATE, "440")));
        when(xboxStats.forTitle(eq(user), eq("1144039928"), any(), any(), eq(true)))
                .thenReturn(Optional.of(PlatformStats.of(Status.UNSUPPORTED, "1144039928")));

        mvc.perform(v3(get(STATS + "/steam/440")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("private"));
        mvc.perform(v3(get(STATS + "/xbox/1144039928")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsupported"));
    }

    @Test
    void unlinkedPlatformIs404() throws Exception {
        when(steamStats.forGame(any(), any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());
        when(xboxStats.forTitle(any(), any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());
        when(raStats.forGame(any(), anyInt(), any(), anyBoolean())).thenReturn(Optional.empty());
        when(raStats.account(any(), any())).thenReturn(Optional.empty());

        for (String path : List.of("/steam/440", "/xbox/1144039928", "/retroachievements/228", "/retroachievements")) {
            mvc.perform(v3(get(STATS + path)))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("Content-Type", "application/problem+json"))
                    .andExpect(jsonPath("$.code").value("not_found"));
        }
    }

    @Test
    void malformedIdIs400() throws Exception {
        for (String path : List.of("/steam/abc", "/xbox/12345678901", "/retroachievements/1234567890")) {
            mvc.perform(v3(get(STATS + path)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        verifyNoInteractions(steamStats, xboxStats, raStats);
    }

    @Test
    void memberBudgetSpentIs429() throws Exception {
        budgetLeft = false;

        for (String path : List.of("/steam/440", "/xbox/1144039928", "/retroachievements/228")) {
            mvc.perform(v3(get(STATS + path)))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "42"))
                    .andExpect(jsonPath("$.code").value("too_many_requests"));
        }
        verifyNoInteractions(steamStats, xboxStats, raStats);
    }

    @Test
    void oneCallIsOneRefresh() throws Exception {
        when(raStats.forGame(eq(user), eq(228), any(), eq(true))).thenAnswer(invocation -> {
            FetchBudget budget = invocation.getArgument(2);
            budgetLeft = false;
            // already granted for this call: the upstream calls it makes are not charged again
            return budget.tryAcquire() && budget.tryAcquire()
                    ? Optional.of(PlatformStats.of(Status.OK, "228")) : Optional.empty();
        });

        mvc.perform(v3(get(STATS + "/retroachievements/228")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(228));
    }

    @Test
    void retroAchievementsAccount() throws Exception {
        when(raStats.account(eq(user), eq(FetchBudget.UNLIMITED))).thenReturn(Optional.of(new RaAccountView(
                Status.OK, "Runner", "https://retroachievements.org/user/Runner", null, 1200, 30, 4000, "Playing",
                228, List.of(), List.of())));

        mvc.perform(v3(get(STATS + "/retroachievements")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("Runner"))
                .andExpect(jsonPath("$.totalPoints").value(1200))
                .andExpect(jsonPath("$.status").value("ok"));
        verify(budgets, never()).forMember(any());
    }

    @Test
    void withoutToken_401() throws Exception {
        mvc.perform(get(STATS + "/steam/440").header("X-API-Version", "3"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- Chess

    @Test
    void chessDetailIsTheSameProjectionWithTheDailyGames() throws Exception {
        var game = new fr.enimaloc.catapult.service.enrichment.chess.ChesscomDailyGame(
                "https://www.chess.com/game/daily/1", "black", "rival", true, null, "chess");
        var chesscom = new fr.enimaloc.catapult.service.enrichment.chess.ChessStats.Chesscom(Status.OK, "hikaru",
                "https://www.chess.com/member/hikaru", Map.of("blitz",
                new fr.enimaloc.catapult.service.enrichment.chess.ChessRating(3449, 30, null, null, 3503, 10, 5, 3,
                        2, null)), 2814, 1, 1, List.of(game));
        when(chessStats.forMember(eq(user), any(), eq(true))).thenReturn(Optional.of(
                new fr.enimaloc.catapult.service.enrichment.chess.ChessStats(Status.OK, "chess", "hikaru", chesscom,
                        null, null)));

        mvc.perform(v3(get(STATS + "/chess")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.game").value("chess"))
                .andExpect(jsonPath("$.account").value("hikaru"))
                .andExpect(jsonPath("$.chesscom.ratings.blitz.rating").value(3449))
                .andExpect(jsonPath("$.chesscom.games[0].opponent").value("rival"))
                .andExpect(jsonPath("$.lichess").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.more").value("http://localhost/api/me/stats/chess?version=3"));
        org.assertj.core.api.Assertions.assertThat(MeStatsController.class.getMethod("chess", UUID.class)
                .getReturnType()).isEqualTo(fr.enimaloc.catapult.service.enrichment.chess.ChessStats.class);
    }

    @Test
    void chessWithoutAccountIs404() throws Exception {
        when(chessStats.forMember(any(), any(), anyBoolean())).thenReturn(Optional.empty());

        mvc.perform(v3(get(STATS + "/chess")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void chessBudgetSpentIs429() throws Exception {
        budgetLeft = false;

        mvc.perform(v3(get(STATS + "/chess")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"));
        verifyNoInteractions(chessStats);
    }

    // ---------------------------------------------------------------- Blizzard

    private static final BlizzardStats.WowStats WOW_DETAIL = new BlizzardStats.WowStats(Status.OK, "Inspectordob",
            "Ravencrest", 90, "Evoker", "Preservation", "Dracthyr", "ALLIANCE", null, 28_755, 290, 288, null, null,
            null, List.of(), List.of(new BlizzardStats.WowStats.Character("eu", "Inspectordob", "Ravencrest", 90,
                    "Evoker", "ALLIANCE", true)));

    @Test
    void blizzardDetailIsTheSameProjection() throws Exception {
        when(blizzardStats.forGame(eq(user), eq(BlizzardGame.WOW), eq(Locale.FRENCH), any(), eq(true)))
                .thenReturn(Optional.of(new BlizzardStats(Status.OK, BlizzardGame.WOW, "Name#1234", "eu",
                        WOW_DETAIL, null, null, null)));

        mvc.perform(v3(get(STATS + "/blizzard/wow")).param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.game").value("wow"))
                .andExpect(jsonPath("$.account").value("Name#1234"))
                .andExpect(jsonPath("$.region").value("eu"))
                .andExpect(jsonPath("$.wow.name").value("Inspectordob"))
                .andExpect(jsonPath("$.wow.characters[0].main").value(true))
                .andExpect(jsonPath("$.wow.characters[0].id").doesNotExist())
                .andExpect(jsonPath("$.more").value("http://localhost/api/me/stats/blizzard/wow?version=3"));
        // the aggregate's record itself
        org.assertj.core.api.Assertions.assertThat(MeStatsController.class.getMethod("blizzard", UUID.class,
                String.class, String.class, String.class).getReturnType()).isEqualTo(BlizzardStats.class);
    }

    @Test
    void blizzardBudgetSpentIs429() throws Exception {
        budgetLeft = false;

        mvc.perform(v3(get(STATS + "/blizzard/d3")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("too_many_requests"));
        verifyNoInteractions(blizzardStats);
    }

    @Test
    void unknownBlizzardGameIs400() throws Exception {
        // Hearthstone has no personal stats; Overwatch is not a Blizzard game of the enrichment
        for (String game : List.of("hearthstone", "ow", "WOW")) {
            mvc.perform(v3(get(STATS + "/blizzard/" + game)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        verifyNoInteractions(blizzardStats);
    }

    @Test
    void noLinkIs404() throws Exception {
        when(blizzardStats.forGame(any(), any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());

        for (String game : List.of("wow", "sc2", "d3")) {
            mvc.perform(v3(get(STATS + "/blizzard/" + game)))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("Content-Type", "application/problem+json"))
                    .andExpect(jsonPath("$.code").value("not_found"));
        }
        mvc.perform(v3(get(STATS + "/blizzard/wow")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("import")));
    }

    @Test
    void osuDetailIsTheSameProjection_withEveryRulesetAndTheLatestScores() throws Exception {
        var score = new fr.enimaloc.catapult.service.enrichment.osu.OsuStats.Score("Some Song", "Some Artist",
                "Insane", 5.42, "https://osu.ppy.sh/b/1001", "S", 256.43, 97.51, 790, List.of("HD"), null);
        var taiko = new fr.enimaloc.catapult.service.enrichment.osu.OsuStats.RulesetStats(150.0, null, null, 91.2,
                120L, 3_600L, 20);
        when(osuStats.forGame(eq(user),
                eq(fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.OsuGame.OSU), any(), eq(true)))
                .thenReturn(Optional.of(new fr.enimaloc.catapult.service.enrichment.osu.OsuStats(Status.OK,
                        fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.OsuGame.OSU, "Inspector",
                        new fr.enimaloc.catapult.service.enrichment.osu.OsuStats.Profile(Status.OK, "osu", 4321.5,
                                12_345, 678, "FR", 98.77, 23_456L, 1_234_567L, 101, score,
                                java.util.Map.of("taiko", taiko), List.of(score, score)), null)));

        mvc.perform(v3(get(STATS + "/osu/osu")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.game").value("osu"))
                .andExpect(jsonPath("$.account").value("Inspector"))
                .andExpect(jsonPath("$.osu.globalRank").value(12_345))
                .andExpect(jsonPath("$.osu.rulesets.taiko.pp").value(150.0))
                .andExpect(jsonPath("$.osu.recentScores[1].rank").value("S"))
                .andExpect(jsonPath("$.more").value("http://localhost/api/me/stats/osu/osu?version=3"));
        org.assertj.core.api.Assertions.assertThat(MeStatsController.class.getMethod("osu", UUID.class,
                String.class).getReturnType())
                .isEqualTo(fr.enimaloc.catapult.service.enrichment.osu.OsuStats.class);
    }

    @Test
    void osuWithoutALink_is404_andAnUnknownGame400() throws Exception {
        when(osuStats.forGame(any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());

        mvc.perform(v3(get(STATS + "/osu/osu")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(v3(get(STATS + "/osu/taiko")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bungieDetailIsTheSameProjection_withEveryCharacter() throws Exception {
        var hunter = new fr.enimaloc.catapult.service.enrichment.bungie.BungieStats.Character("hunter", 2010, 61_440,
                java.time.Instant.parse("2026-11-22T21:15:03Z"), null);
        var titan = new fr.enimaloc.catapult.service.enrichment.bungie.BungieStats.Character("titan", 1990, 12_000,
                null, null);
        when(bungieStats.forGame(eq(user),
                eq(fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BungieGame.DESTINY2),
                eq(Locale.FRENCH), any(), eq(true)))
                .thenReturn(Optional.of(new fr.enimaloc.catapult.service.enrichment.bungie.BungieStats(Status.OK,
                        fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BungieGame.DESTINY2,
                        "Guardian#0042", new fr.enimaloc.catapult.service.enrichment.bungie.BungieStats.Destiny2Stats(
                        Status.OK, "steam", 8, 18_250, null, hunter, null, List.of(hunter, titan)), null)));

        mvc.perform(v3(get(STATS + "/bungie/destiny2")).param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.game").value("destiny2"))
                .andExpect(jsonPath("$.account").value("Guardian#0042"))
                .andExpect(jsonPath("$.destiny2.guardianRank").value(8))
                .andExpect(jsonPath("$.destiny2.characters[1].className").value("titan"))
                .andExpect(jsonPath("$.more").value("http://localhost/api/me/stats/bungie/destiny2?version=3"));
        org.assertj.core.api.Assertions.assertThat(MeStatsController.class.getMethod("bungie", UUID.class,
                String.class, String.class, String.class).getReturnType())
                .isEqualTo(fr.enimaloc.catapult.service.enrichment.bungie.BungieStats.class);
    }

    @Test
    void bungieWithoutALink_is404_anUnknownGame400_andASpentBudget429() throws Exception {
        when(bungieStats.forGame(any(), any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());

        mvc.perform(v3(get(STATS + "/bungie/destiny2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(v3(get(STATS + "/bungie/destiny1")))
                .andExpect(status().isBadRequest());

        budgetLeft = false;
        mvc.perform(v3(get(STATS + "/bungie/destiny2")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void hypixelDetailIsTheSameProjection_withTheModesDetails() throws Exception {
        var stats = new fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStats(Status.OK,
                fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.HypixelGame.HYPIXEL, "Notch",
                new fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStats.HypixelNetworkStats(87, 9_997_500L,
                        1_234_567, 5_420, "MVP_PLUS", null),
                new fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStats.HypixelBedWarsStats(4, 120, 98, 310,
                        102, 145),
                null,
                new fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStats.HypixelOnlineStatus(Status.PRIVATE,
                        null, null, null, null, null),
                null);
        when(hypixelStats.forMember(eq(user), any(), eq(true))).thenReturn(Optional.of(stats));

        mvc.perform(v3(get(STATS + "/hypixel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.game").value("hypixel"))
                .andExpect(jsonPath("$.account").value("Notch"))
                .andExpect(jsonPath("$.network.level").value(87))
                .andExpect(jsonPath("$.bedwars.finalKills").value(310))
                .andExpect(jsonPath("$.online.status").value("private"))
                .andExpect(jsonPath("$.more").value("http://localhost/api/me/stats/hypixel?version=3"));
    }

    @Test
    void hypixelWithoutAnAccount_is404_andASpentBudget429() throws Exception {
        when(hypixelStats.forMember(any(), any(), anyBoolean())).thenReturn(Optional.empty());

        mvc.perform(v3(get(STATS + "/hypixel")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));

        budgetLeft = false;
        mvc.perform(v3(get(STATS + "/hypixel")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void supercellDetailIsTheSameProjection_withTheDetailOnlyFields() throws Exception {
        var coc = new fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats.ClashOfClansStats(16, 3, 240,
                5200, 5600, "Legend League", null, 1800, 10, 3900,
                new fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats.Clan("#2Y0", "Catapult"), 412, 37,
                java.util.List.of(new fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats.Hero(
                        "Archer Queen", 92, 95, "home")));
        var stats = new fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats(Status.OK,
                fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame.CLASH_OF_CLANS, "Chief", "#2PP", coc,
                null, null, null);
        when(supercellStats.forGame(eq(user),
                eq(fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame.CLASH_OF_CLANS), any(), eq(true)))
                .thenReturn(Optional.of(stats));

        mvc.perform(v3(get(STATS + "/supercell/clash-of-clans")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.game").value("clash-of-clans"))
                .andExpect(jsonPath("$.account").value("Chief"))
                .andExpect(jsonPath("$.tag").value("#2PP"))
                .andExpect(jsonPath("$.clashOfClans.townHallLevel").value(16))
                .andExpect(jsonPath("$.clashOfClans.heroes[0].name").value("Archer Queen"))
                .andExpect(jsonPath("$.more")
                        .value("http://localhost/api/me/stats/supercell/clash-of-clans?version=3"));
    }

    @Test
    void supercellWithoutATag_is404_anUnknownGame400_andASpentBudget429() throws Exception {
        when(supercellStats.forGame(any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());

        mvc.perform(v3(get(STATS + "/supercell/brawl-stars")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(v3(get(STATS + "/supercell/hay-day")))
                .andExpect(status().isBadRequest());

        budgetLeft = false;
        mvc.perform(v3(get(STATS + "/supercell/clash-royale")))
                .andExpect(status().isTooManyRequests());
    }

    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }

    // ---------------------------------------------------------------- Riot

    @Test
    void riotDetailIsTheSameProjection() throws Exception {
        var queues = List.of(new fr.enimaloc.catapult.service.enrichment.riot.RiotStats.Queue("RANKED_SOLO_5x5",
                new fr.enimaloc.catapult.service.enrichment.riot.RiotStats.Rank("GOLD", "II", 57, 61, 54)));
        when(riotStats.forGame(eq(user), eq(fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.RiotGame.LOL),
                eq(Locale.FRENCH), any(), eq(true))).thenReturn(Optional.of(
                new fr.enimaloc.catapult.service.enrichment.riot.RiotStats(Status.OK,
                        fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.RiotGame.LOL,
                        "Catapult#EUW", "euw1", new fr.enimaloc.catapult.service.enrichment.riot.RiotStats.LolStats(
                        Status.OK, 312, null, null, null, null, queues), null, null)));

        mvc.perform(v3(get(STATS + "/riot/lol")).param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game").value("lol"))
                .andExpect(jsonPath("$.account").value("Catapult#EUW"))
                .andExpect(jsonPath("$.lol.queues[0].queueType").value("RANKED_SOLO_5x5"))
                .andExpect(jsonPath("$.more").value("http://localhost/api/me/stats/riot/lol?version=3"));
        org.assertj.core.api.Assertions.assertThat(MeStatsController.class.getMethod("riot", UUID.class,
                String.class, String.class, String.class).getReturnType())
                .isEqualTo(fr.enimaloc.catapult.service.enrichment.riot.RiotStats.class);
    }

    @Test
    void riotTft_andTheBudget_andUnknownGames() throws Exception {
        when(riotStats.forGame(any(), any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());
        mvc.perform(v3(get(STATS + "/riot/tft"))).andExpect(status().isNotFound());
        verify(riotStats).forGame(eq(user), eq(fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.RiotGame.TFT),
                any(), any(), eq(true));

        for (String game : List.of("valorant", "LOL", "lor")) {
            mvc.perform(v3(get(STATS + "/riot/" + game))).andExpect(status().isBadRequest());
        }

        budgetLeft = false;
        mvc.perform(v3(get(STATS + "/riot/lol"))).andExpect(status().isTooManyRequests());
    }

    // ---------------------------------------------------------------- FACEIT

    @Test
    void faceitDetailIsTheSameProjection_withTheRecentResults() throws Exception {
        var cs2 = new fr.enimaloc.catapult.service.enrichment.faceit.FaceitStats.Cs2Stats(Status.OK, 8, 1850, "EU",
                420, 231, 55, 1.12, 48, 2, 9, null, List.of(true, false));
        when(faceitStats.forGame(eq(user),
                eq(fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.FaceitGame.CS2), any(), eq(true)))
                .thenReturn(Optional.of(new fr.enimaloc.catapult.service.enrichment.faceit.FaceitStats(Status.OK,
                        fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.FaceitGame.CS2,
                        "Catapult", cs2, null)));

        mvc.perform(v3(get(STATS + "/faceit/cs2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.game").value("cs2"))
                .andExpect(jsonPath("$.account").value("Catapult"))
                .andExpect(jsonPath("$.cs2.elo").value(1850))
                .andExpect(jsonPath("$.cs2.recentResults[0]").value(true))
                .andExpect(jsonPath("$.more").value("http://localhost/api/me/stats/faceit/cs2?version=3"));
        org.assertj.core.api.Assertions.assertThat(MeStatsController.class.getMethod("faceit", UUID.class,
                String.class).getReturnType())
                .isEqualTo(fr.enimaloc.catapult.service.enrichment.faceit.FaceitStats.class);
    }

    @Test
    void faceitBudgetSpentIs429_unknownGame400_noNickname404() throws Exception {
        mvc.perform(v3(get(STATS + "/faceit/csgo"))).andExpect(status().isBadRequest());
        verifyNoInteractions(faceitStats);

        when(faceitStats.forGame(any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());
        mvc.perform(v3(get(STATS + "/faceit/cs2"))).andExpect(status().isNotFound());

        budgetLeft = false;
        mvc.perform(v3(get(STATS + "/faceit/cs2"))).andExpect(status().isTooManyRequests());
    }
}
