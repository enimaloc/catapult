package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.api.userapi.GameInfoAssembler;
import fr.enimaloc.catapult.api.userapi.MoreLinks;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardGameDataService;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardPublisher;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardRegion;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStats;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStatsService;
import fr.enimaloc.catapult.service.enrichment.hltb.HltbService;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BlizzardGame;
import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticGame;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticService;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.prices.GamePrices;
import fr.enimaloc.catapult.service.enrichment.prices.PricesService;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.rastats.RetroAchievementsStatsService;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunEnrichment;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunGame;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunGameService;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentity;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentityResolver;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunPersonalService;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRecords;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRun;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckService;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbService;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.enrichment.steamstats.SteamStatsService;
import fr.enimaloc.catapult.service.enrichment.xboxstats.XboxStatsService;
import fr.enimaloc.catapult.service.enrichment.xboxstats.XboxStatsUnavailableException;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.enrichment.trailer.TrailerSourceResolver;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivity;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The parallel, time-boxed gathering of the five enrichment sources and the trailer link. */
class GameEnrichmentAssemblerTest {

    private static final String BASE = "https://catapult.test";
    private static final MoreLinks LINKS = new MoreLinks(BASE, GameInfoAssembler.LinkStyle.CURRENT);
    private static final GamePrices PRICES = new GamePrices("itad-1", "FR", "EUR",
            new GamePrices.Deal("61", "Steam", new BigDecimal("9.99"), new BigDecimal("19.99"), 50,
                    "https://store.steampowered.com/app/620"), null, List.of(), "https://isthereanydeal.com/game/portal-2/");
    private static final OpenCriticGame CRITIC = new OpenCriticGame(7L, "Portal 2", 95, 99, "Mighty", 50,
            "https://opencritic.com/game/7/portal-2");
    private static final TwitchActivity ACTIVITY = new TwitchActivity("490377", "Portal 2", 3, 120L, false,
            List.of(), List.of());
    private static final DetectedGame PORTAL = new DetectedGame("620", GameBinding.SourceType.STEAM, "Portal 2");
    private static final DetectedGame HALO = new DetectedGame("9NP1P1WFS0LB", GameBinding.SourceType.XBOX, "Halo");

    private static final TimeToBeat TTB = new TimeToBeat(1L, 2L, 3L, 4);
    private static final ProtonDbSummary PROTON = new ProtonDbSummary("platinum", "platinum", "platinum",
            "strong", 0.9, 100, "https://www.protondb.com/app/620");
    private static final SteamGridDbAssets SGDB = new SteamGridDbAssets(5L, "Portal 2",
            "https://www.steamgriddb.com/game/5", List.of(), List.of(), List.of(), List.of());
    private static final SteamDeckCompatibility DECK = new SteamDeckCompatibility(
            SteamDeckCompatibility.Category.VERIFIED, List.of());
    private static final PcGamingWikiPage PCGW = new PcGamingWikiPage(42L, "Portal 2",
            "https://www.pcgamingwiki.com/wiki/Portal_2", "true", "true", "false");

    private final HltbService hltb = mock(HltbService.class);
    private final ProtonDbService protonDb = mock(ProtonDbService.class);
    private final SteamGridDbService steamGridDb = mock(SteamGridDbService.class);
    private final SteamDeckService steamDeck = mock(SteamDeckService.class);
    private final PcGamingWikiService pcgw = mock(PcGamingWikiService.class);
    private final PricesService prices = mock(PricesService.class);
    private final OpenCriticService openCritic = mock(OpenCriticService.class);
    private final TwitchActivityService twitch = mock(TwitchActivityService.class);
    private final SpeedrunGameService speedrunGames = mock(SpeedrunGameService.class);
    private final SpeedrunIdentityResolver speedrunIdentity = mock(SpeedrunIdentityResolver.class);
    private final SpeedrunPersonalService speedrunPersonal = mock(SpeedrunPersonalService.class);
    private final IgdbService igdbService = mock(IgdbService.class);
    private final IgdbGameDetailsService igdbGameDetailsService = mock(IgdbGameDetailsService.class);
    private final TrailerSourceResolver trailers = mock(TrailerSourceResolver.class);
    private final SteamStatsService steamStats = mock(SteamStatsService.class);
    private final XboxStatsService xboxStats = mock(XboxStatsService.class);
    private final RetroAchievementsStatsService raStats = mock(RetroAchievementsStatsService.class);
    private final BlizzardStatsService blizzardStats = mock(BlizzardStatsService.class);
    private final BlizzardGameDataService blizzardData = mock(BlizzardGameDataService.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();

    private GameEnrichmentAssembler assembler;

    @BeforeEach
    void setUp() {
        properties.setAggregateTimeout(Duration.ofSeconds(2));
        assembler = new GameEnrichmentAssembler(hltb, protonDb, steamGridDb, steamDeck, pcgw, prices, openCritic,
                twitch, speedrunGames, speedrunIdentity, speedrunPersonal, igdbService, igdbGameDetailsService,
                trailers, steamStats, xboxStats, raStats, blizzardStats, blizzardData, properties);
        when(hltb.timeToBeat(any(), any())).thenReturn(Optional.of(TTB));
        when(protonDb.summary(anyString(), any())).thenReturn(Optional.of(PROTON));
        when(steamGridDb.bySteamAppId(anyString(), any())).thenReturn(Optional.of(SGDB));
        when(steamGridDb.byName(anyString(), any())).thenReturn(Optional.of(SGDB));
        when(steamDeck.compatibility(anyString(), any())).thenReturn(Optional.of(DECK));
        when(pcgw.bySteamAppId(anyString(), any())).thenReturn(Optional.of(PCGW));
        when(pcgw.byName(anyString(), any())).thenReturn(Optional.of(PCGW));
        when(prices.bySteamAppId(anyString(), anyString(), any())).thenReturn(Optional.of(PRICES));
        when(prices.byName(anyString(), anyString(), any())).thenReturn(Optional.of(PRICES));
        when(openCritic.byName(anyString(), any())).thenReturn(Optional.of(CRITIC));
        when(twitch.byIgdbId(anyString(), any(), any())).thenReturn(Optional.of(ACTIVITY));
        when(trailers.resolve(any(), any(), any())).thenReturn(Optional.empty());
        when(igdbGameDetailsService.getDetails(anyString())).thenReturn(Optional.empty());
        when(igdbService.findExternalUid(anyString(), any())).thenReturn(Optional.empty());
        when(speedrunGames.byName(anyString(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        assembler.shutdown();
    }

    @Test
    void steamGame_allSourcesQueriedInParallel() {
        Answer<Object> slowProton = slow(Duration.ofMillis(700), Optional.of(PROTON));
        when(protonDb.summary(anyString(), any())).thenAnswer(slowProton);
        when(steamDeck.compatibility(anyString(), any())).thenAnswer(slow(Duration.ofMillis(700), Optional.of(DECK)));
        when(hltb.timeToBeat(any(), any())).thenAnswer(slow(Duration.ofMillis(700), Optional.of(TTB)));
        when(pcgw.bySteamAppId(anyString(), any())).thenAnswer(slow(Duration.ofMillis(700), Optional.of(PCGW)));
        when(steamGridDb.bySteamAppId(anyString(), any()))
                .thenAnswer(slow(Duration.ofMillis(700), Optional.of(SGDB)));

        long start = System.nanoTime();
        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.hltb()).isEqualTo(TTB);
        assertThat(result.protondb()).isEqualTo(PROTON);
        assertThat(result.steamgriddb()).isEqualTo(SGDB);
        assertThat(result.steamDeck()).isEqualTo(DECK);
        assertThat(result.pcgamingwiki()).isEqualTo(PCGW);
        assertThat(result.steamAppId()).isEqualTo("620");
        // five 700 ms calls one after the other would take 3.5 s
        assertThat(took).isLessThan(Duration.ofMillis(1800));
        verify(hltb).timeToBeat(eq("72"), eq(FetchBudget.UNLIMITED));
        verify(protonDb).summary(eq("620"), eq(FetchBudget.UNLIMITED));
        verify(steamGridDb, never()).byName(anyString(), any());
        verify(pcgw, never()).byName(anyString(), any());
        verify(igdbService, never()).findExternalUid(anyString(), any());
    }

    @Test
    void aFailingSourceThatBacksOff_isNull_othersPresent() {
        when(protonDb.summary(anyString(), any())).thenThrow(new EnrichmentUnavailableException(Duration.ofMinutes(1)));

        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);

        assertThat(result.protondb()).isNull();
        assertThat(result.hltb()).isEqualTo(TTB);
    }

    @Test
    void slowSource_nullAfterTimeout_othersPresent() {
        when(protonDb.summary(anyString(), any())).thenAnswer(slow(Duration.ofSeconds(5), Optional.of(PROTON)));

        long start = System.nanoTime();
        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.protondb()).isNull();
        assertThat(result.hltb()).isEqualTo(TTB);
        assertThat(result.steamgriddb()).isEqualTo(SGDB);
        assertThat(result.steamDeck()).isEqualTo(DECK);
        assertThat(result.pcgamingwiki()).isEqualTo(PCGW);
        assertThat(took).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void newSources_askedWithCountryLanguageAndIds() {
        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "US",
                "de", LINKS, Optional.empty(), Locale.ENGLISH);

        assertThat(result.prices()).isEqualTo(PRICES);
        assertThat(result.opencritic()).isEqualTo(CRITIC);
        assertThat(result.twitch()).isEqualTo(ACTIVITY);
        verify(prices).bySteamAppId(eq("620"), eq("US"), eq(FetchBudget.UNLIMITED));
        verify(openCritic).byName(eq("Portal 2"), eq(FetchBudget.UNLIMITED));
        verify(twitch).byIgdbId(eq("72"), eq("de"), eq(FetchBudget.UNLIMITED));
    }

    @Test
    void openCritic_searchedByTheCachedIgdbName() {
        when(igdbService.getGameCache()).thenReturn(Map.of("72", "Portal 2"));

        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"),
                "Portal 2 - Game of the Year Edition", "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);

        assertThat(result.opencritic()).isEqualTo(CRITIC);
        verify(openCritic).byName(eq("Portal 2"), eq(FetchBudget.UNLIMITED));
    }

    @Test
    void newSources_withoutIgdbOrSteamId_pricesByNameOnly() {
        GameEnrichment result = assembler.enrich(HALO, Optional.empty(), Optional.empty(), "Halo", "FR", null,
                LINKS, Optional.empty(), Locale.ENGLISH);

        assertThat(result.prices()).isEqualTo(PRICES);
        assertThat(result.opencritic()).isNull();
        assertThat(result.twitch()).isNull();
        verify(prices).byName(eq("Halo"), eq("FR"), eq(FetchBudget.UNLIMITED));
        verify(prices, never()).bySteamAppId(anyString(), anyString(), any());
        verify(openCritic, never()).byName(any(), any());
        verify(twitch, never()).byIgdbId(any(), any(), any());
    }

    @Test
    void slowTwitchDoesNotBlockOthers() {
        when(twitch.byIgdbId(anyString(), any(), any())).thenAnswer(slow(Duration.ofSeconds(5), Optional.of(ACTIVITY)));

        long start = System.nanoTime();
        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR",
                null, LINKS, Optional.empty(), Locale.ENGLISH);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.twitch()).isNull();
        assertThat(result.prices()).isEqualTo(PRICES);
        assertThat(result.opencritic()).isEqualTo(CRITIC);
        assertThat(result.protondb()).isEqualTo(PROTON);
        assertThat(took).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void failingSource_null_noException() {
        when(steamDeck.compatibility(anyString(), any())).thenThrow(new IllegalStateException("boom"));
        when(hltb.timeToBeat(any(), any()))
                .thenThrow(new UpstreamBudgetExceededException(Duration.ofMinutes(1)));

        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);

        assertThat(result.steamDeck()).isNull();
        assertThat(result.hltb()).isNull();
        assertThat(result.protondb()).isEqualTo(PROTON);
        assertThat(result.pcgamingwiki()).isEqualTo(PCGW);
    }

    @Test
    void aggregate_nonSteamWithoutAppId_namesOnly() {
        GameEnrichment result = assembler.enrich(HALO, Optional.empty(), Optional.empty(), "Halo", "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);

        assertThat(result.protondb()).isNull();
        assertThat(result.steamDeck()).isNull();
        assertThat(result.steamAppId()).isNull();
        assertThat(result.steamgriddb()).isEqualTo(SGDB);
        assertThat(result.pcgamingwiki()).isEqualTo(PCGW);
        verify(steamGridDb).byName(eq("Halo"), eq(FetchBudget.UNLIMITED));
        verify(pcgw).byName(eq("Halo"), eq(FetchBudget.UNLIMITED));
        verify(protonDb, never()).summary(anyString(), any());
        verify(steamDeck, never()).compatibility(anyString(), any());
        verify(hltb).timeToBeat(eq(null), eq(FetchBudget.UNLIMITED));
    }

    @Test
    void xboxGameWithIgdbSteamMapping_usesResolvedAppId() {
        when(igdbService.findExternalUid("1234", GameBinding.SourceType.STEAM)).thenReturn(Optional.of("976730"));

        GameEnrichment result = assembler.enrich(HALO, Optional.of("1234"), Optional.empty(), "Halo", "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);

        assertThat(result.steamAppId()).isEqualTo("976730");
        assertThat(result.protondb()).isEqualTo(PROTON);
        assertThat(result.steamDeck()).isEqualTo(DECK);
        verify(protonDb).summary(eq("976730"), any());
        verify(steamDeck).compatibility(eq("976730"), any());
        verify(steamGridDb).bySteamAppId(eq("976730"), any());
        verify(pcgw).bySteamAppId(eq("976730"), any());
        verify(steamGridDb, never()).byName(anyString(), any());
    }

    @Test
    void trailer_prefersMp4_thenYoutube() {
        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId("72");
        details.setVideoIds(List.of("dQw4w9WgXcQ", "other"));
        when(igdbGameDetailsService.getDetails("72")).thenReturn(Optional.of(details));

        // Steam: the resolver finds an HLS H.264 stream for the detected app id
        URI hls = URI.create("https://video.akamai.steamstatic.com/hls_264_master.m3u8");
        when(trailers.resolve(eq("620"), isNull(), eq(FetchBudget.UNLIMITED))).thenReturn(Optional.of(hls));
        GameEnrichment steam = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);
        assertThat(steam.trailer()).isEqualTo(new GameEnrichment.Trailer("mp4", BASE + "/api/me/game/trailer.mp4?version=3"));

        // Xbox: IGDB's Steam mapping first, then the detected product, in the resolver's order
        when(igdbService.findExternalUid("72", GameBinding.SourceType.STEAM)).thenReturn(Optional.of("976730"));
        when(trailers.resolve(eq("976730"), eq(HALO.getSourceId()), eq(FetchBudget.UNLIMITED)))
                .thenReturn(Optional.of(hls));
        GameEnrichment xbox = assembler.enrich(HALO, Optional.of("72"), Optional.empty(), "Halo", "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);
        assertThat(xbox.trailer()).isEqualTo(new GameEnrichment.Trailer("mp4", BASE + "/api/me/game/trailer.mp4?version=3"));
        when(igdbService.findExternalUid("72", GameBinding.SourceType.STEAM)).thenReturn(Optional.empty());

        // no HLS stream (none, or the trailer source switched off): IGDB's first video, on YouTube
        when(trailers.resolve(eq("620"), isNull(), any())).thenReturn(Optional.empty());
        GameEnrichment youtube = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);
        assertThat(youtube.trailer())
                .isEqualTo(new GameEnrichment.Trailer("youtube", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"));

        // nothing at all
        GameEnrichment none = assembler.enrich(PORTAL, Optional.empty(), Optional.of("620"), "Portal 2", "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);
        assertThat(none.trailer()).isNull();
    }

    @Test
    void enrichAsync_completesWithTheEnrichment() {
        GameEnrichment result = assembler.enrichAsync(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2",
                "FR", null, LINKS, Optional.empty(), Locale.ENGLISH).join();

        assertThat(result.protondb()).isEqualTo(PROTON);
        assertThat(result.steamAppId()).isEqualTo("620");
    }

    @Test
    void wiresInASpringContext() {
        new ApplicationContextRunner()
                // IgdbService declares @DependsOn("flyway")
                .withBean("flyway", Object.class, Object::new)
                .withBean(HltbService.class, () -> hltb)
                .withBean(ProtonDbService.class, () -> protonDb)
                .withBean(SteamGridDbService.class, () -> steamGridDb)
                .withBean(SteamDeckService.class, () -> steamDeck)
                .withBean(PcGamingWikiService.class, () -> pcgw)
                .withBean(PricesService.class, () -> prices)
                .withBean(OpenCriticService.class, () -> openCritic)
                .withBean(TwitchActivityService.class, () -> twitch)
                .withBean(SpeedrunGameService.class, () -> speedrunGames)
                .withBean(SpeedrunIdentityResolver.class, () -> speedrunIdentity)
                .withBean(SpeedrunPersonalService.class, () -> speedrunPersonal)
                .withBean(IgdbService.class, () -> igdbService)
                .withBean(IgdbGameDetailsService.class, () -> igdbGameDetailsService)
                .withBean(TrailerSourceResolver.class, () -> trailers)
                .withBean(SteamStatsService.class, () -> steamStats)
                .withBean(XboxStatsService.class, () -> xboxStats)
                .withBean(RetroAchievementsStatsService.class, () -> raStats)
                .withBean(BlizzardStatsService.class, () -> blizzardStats)
                .withBean(BlizzardGameDataService.class, () -> blizzardData)
                .withBean(EnrichmentProperties.class)
                .withUserConfiguration(GameEnrichmentAssembler.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(GameEnrichmentAssembler.class));
    }

    // ---------------------------------------------------------------- speedrun.com

    private static final SpeedrunGame CELESTE = new SpeedrunGame("o1y9j9v6", "Celeste", "Celeste", "celeste",
            "https://www.speedrun.com/celeste", "https://www.speedrun.com/static/game/o1y9j9v6/cover.png");
    private static final SpeedrunRecords RECORDS = new SpeedrunRecords(CELESTE, List.of(
            new SpeedrunRecords.CategoryRecord("c1", "Any%", "https://www.speedrun.com/celeste?x=c1",
                    new BigDecimal("1484.083"), "ingame", List.of(new SpeedrunRecords.Runner("Marlin", null)),
                    LocalDate.of(2026, 10, 18), "https://www.speedrun.com/celeste/run/r1", null)));
    private static final SpeedrunIdentity RUNNER = new SpeedrunIdentity("u1", "linkus7",
            "https://www.speedrun.com/users/linkus7", SpeedrunIdentity.Source.TWITCH);
    private static final List<SpeedrunRun> PBS = List.of(new SpeedrunRun("o1y9j9v6", "Celeste", "c1", "Any%", 42,
            new BigDecimal("1612.5"), LocalDate.of(2026, 9, 1), null, "https://www.speedrun.com/celeste/run/pb"));
    private static final List<SpeedrunRun> PENDING = List.of(new SpeedrunRun("o1y9j9v6", "Celeste", "c1", "Any%",
            null, new BigDecimal("1600.0"), LocalDate.of(2026, 11, 20), null,
            "https://www.speedrun.com/celeste/run/new"));

    private static UserAccount member() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        return user;
    }

    private void celesteOnSpeedrun() {
        when(igdbService.getGameCache()).thenReturn(Map.of("72", "Celeste"));
        when(speedrunGames.byName(eq("Celeste"), any())).thenReturn(Optional.of(CELESTE));
        when(speedrunGames.records(eq("o1y9j9v6"), any())).thenReturn(Optional.of(RECORDS));
    }

    @Test
    void speedrun_memberWithAccount_recordsPersonalBestsAndPendingRuns() {
        celesteOnSpeedrun();
        UserAccount user = member();
        when(speedrunIdentity.resolve(eq(user), any())).thenReturn(Optional.of(RUNNER));
        when(speedrunPersonal.personalBests(eq("u1"), eq("o1y9j9v6"), any())).thenReturn(Optional.of(PBS));
        when(speedrunPersonal.apiKey(user, RUNNER)).thenReturn(Optional.of("key"));
        when(speedrunPersonal.pendingRuns(eq(RUNNER), eq("o1y9j9v6"), eq(Optional.of("key")), any()))
                .thenReturn(Optional.of(PENDING));

        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"),
                "Celeste - Farewell Edition", "FR", null, LINKS, Optional.of(user), Locale.ENGLISH);

        assertThat(result.speedrun())
                .isEqualTo(new SpeedrunEnrichment(RECORDS, RUNNER, PBS, PENDING));
        verify(speedrunGames).byName(eq("Celeste"), eq(FetchBudget.UNLIMITED));
        verify(speedrunGames).records(eq("o1y9j9v6"), eq(FetchBudget.UNLIMITED));
        verify(speedrunIdentity).resolve(eq(user), eq(FetchBudget.UNLIMITED));
        verify(speedrunPersonal).personalBests(eq("u1"), eq("o1y9j9v6"), eq(FetchBudget.UNLIMITED));
        verify(speedrunPersonal).pendingRuns(eq(RUNNER), eq("o1y9j9v6"), eq(Optional.of("key")),
                eq(FetchBudget.UNLIMITED));
    }

    @Test
    void speedrun_searchedByTheDetectedNameWithoutIgdbId() {
        when(speedrunGames.byName(eq("Halo"), any())).thenReturn(Optional.of(CELESTE));
        when(speedrunGames.records(eq("o1y9j9v6"), any())).thenReturn(Optional.of(RECORDS));

        GameEnrichment result = assembler.enrich(HALO, Optional.empty(), Optional.empty(), "Halo", "FR", null,
                LINKS, Optional.empty(), Locale.ENGLISH);

        assertThat(result.speedrun()).isEqualTo(new SpeedrunEnrichment(RECORDS, null, null, null));
        verify(speedrunIdentity, never()).resolve(any(), any());
    }

    @Test
    void speedrun_unidentifiedMember_recordsOnly() {
        celesteOnSpeedrun();
        UserAccount user = member();
        when(speedrunIdentity.resolve(eq(user), any())).thenReturn(Optional.empty());

        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Celeste", "FR",
                null, LINKS, Optional.of(user), Locale.ENGLISH);

        assertThat(result.speedrun()).isEqualTo(new SpeedrunEnrichment(RECORDS, null, null, null));
        verify(speedrunPersonal, never()).personalBests(any(), any(), any());
        verify(speedrunPersonal, never()).pendingRuns(any(), any(), any(), any());
    }

    @Test
    void speedrun_failingIdentityOrPersonalBests_keepTheRest() {
        celesteOnSpeedrun();
        UserAccount user = member();
        when(speedrunIdentity.resolve(eq(user), any()))
                .thenThrow(new UpstreamBudgetExceededException(Duration.ofMinutes(1)));

        GameEnrichment noIdentity = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Celeste",
                "FR", null, LINKS, Optional.of(user), Locale.ENGLISH);
        assertThat(noIdentity.speedrun()).isEqualTo(new SpeedrunEnrichment(RECORDS, null, null, null));

        when(speedrunIdentity.resolve(eq(user), any())).thenReturn(Optional.of(RUNNER));
        when(speedrunPersonal.personalBests(any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        when(speedrunPersonal.apiKey(user, RUNNER)).thenReturn(Optional.empty());
        when(speedrunPersonal.pendingRuns(any(), any(), any(), any())).thenReturn(Optional.of(PENDING));

        GameEnrichment noPbs = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Celeste",
                "FR", null, LINKS, Optional.of(user), Locale.ENGLISH);
        assertThat(noPbs.speedrun()).isEqualTo(new SpeedrunEnrichment(RECORDS, RUNNER, null, PENDING));
    }

    @Test
    void speedrun_unknownGameOrNoRecords_isNull() {
        GameEnrichment unknown = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2",
                "FR", null, LINKS, Optional.of(member()), Locale.ENGLISH);
        assertThat(unknown.speedrun()).isNull();
        verify(speedrunIdentity, never()).resolve(any(), any());

        when(speedrunGames.byName(eq("Portal 2"), any())).thenReturn(Optional.of(CELESTE));
        when(speedrunGames.records(eq("o1y9j9v6"), any())).thenReturn(Optional.empty());
        GameEnrichment noRecords = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2",
                "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);
        assertThat(noRecords.speedrun()).isNull();
    }

    @Test
    void slowSpeedrunDoesNotBlockOthers() {
        when(speedrunGames.byName(anyString(), any())).thenAnswer(slow(Duration.ofSeconds(5), Optional.of(CELESTE)));

        long start = System.nanoTime();
        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR",
                null, LINKS, Optional.of(member()), Locale.ENGLISH);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.speedrun()).isNull();
        assertThat(result.hltb()).isEqualTo(TTB);
        assertThat(result.prices()).isEqualTo(PRICES);
        assertThat(took).isLessThan(Duration.ofSeconds(3));
    }

    // ---------------------------------------------------------------- personal stats

    private static final PlatformStats STEAM_OK = PlatformStats.of(Status.OK, "620");
    private static final PlatformStats XBOX_OK = PlatformStats.of(Status.OK, "1144039928");
    private static final PlatformStats RA_OK = PlatformStats.of(Status.OK, "228");

    private void statsEverywhere(UserAccount user) {
        when(steamStats.forGame(eq(user), eq("620"), any(), any(), anyBoolean())).thenReturn(Optional.of(STEAM_OK));
        when(xboxStats.resolveTitle(eq(user), any(), any(), any(), any(), any())).thenReturn(Optional.of("1144039928"));
        when(xboxStats.forTitle(eq(user), eq("1144039928"), any(), any(), anyBoolean()))
                .thenReturn(Optional.of(XBOX_OK));
        when(raStats.resolveGame(eq(user), any(), any(), any())).thenReturn(Optional.of(228));
        when(raStats.forGame(eq(user), eq(228), any(), anyBoolean())).thenReturn(Optional.of(RA_OK));
    }

    @Test
    void publicAggregateHasNoStats() {
        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR",
                null, LINKS, Optional.empty(), Locale.ENGLISH);

        assertThat(result.stats()).isNull();
        verifyNoInteractions(steamStats, xboxStats, raStats);
    }

    @Test
    void memberStats_everyPlatform_summaryOnly() {
        UserAccount user = member();
        statsEverywhere(user);

        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR",
                null, LINKS, Optional.of(user), Locale.FRENCH);

        assertThat(result.stats()).isEqualTo(new PersonalStatsEnrichment(STEAM_OK, XBOX_OK, RA_OK, null,
                GameBinding.SourceType.STEAM));
        verify(steamStats).forGame(user, "620", Locale.FRENCH, FetchBudget.UNLIMITED, false);
        verify(xboxStats).forTitle(user, "1144039928", Locale.FRENCH, FetchBudget.UNLIMITED, false);
        verify(raStats).forGame(user, 228, FetchBudget.UNLIMITED, false);
    }

    @Test
    void steamStatsUseIgdbsAppIdAndXboxTheStoreIdIgdbGives() {
        UserAccount user = member();
        when(igdbService.findExternalUid("1234", GameBinding.SourceType.STEAM)).thenReturn(Optional.of("620"));
        when(igdbService.findExternalUid("1234", GameBinding.SourceType.XBOX)).thenReturn(Optional.of("9NBLGGH"));
        statsEverywhere(user);

        GameEnrichment result = assembler.enrich(HALO, Optional.of("1234"), Optional.empty(), "Halo", "FR", null,
                LINKS, Optional.of(user), Locale.ENGLISH);

        assertThat(result.stats().steam()).isEqualTo(STEAM_OK);
        assertThat(result.stats().detectedOn()).isEqualTo(GameBinding.SourceType.XBOX);
        verify(xboxStats).resolveTitle(eq(user), eq(HALO), eq(Optional.of("9NBLGGH")), eq("Halo"),
                eq(Locale.ENGLISH), any());
    }

    @Test
    void noPlatformAppliesIsNoStats() {
        UserAccount user = member();
        when(steamStats.forGame(any(), any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());
        when(xboxStats.resolveTitle(any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(raStats.resolveGame(any(), any(), any(), any())).thenReturn(Optional.empty());

        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR",
                null, LINKS, Optional.of(user), Locale.ENGLISH);

        assertThat(result.stats()).isNull();
        verify(xboxStats, never()).forTitle(any(), any(), any(), any(), anyBoolean());
        verify(raStats, never()).forGame(any(), anyInt(), any(), anyBoolean());
    }

    @Test
    void outageWhileResolvingIsUnavailable() {
        UserAccount user = member();
        when(steamStats.forGame(any(), any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());
        when(xboxStats.resolveTitle(any(), any(), any(), any(), any(), any()))
                .thenThrow(new XboxStatsUnavailableException("down"));
        when(raStats.resolveGame(any(), any(), any(), any()))
                .thenThrow(new fr.enimaloc.catapult.service.enrichment.rastats.RaUnavailableException("down"));

        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR",
                null, LINKS, Optional.of(user), Locale.ENGLISH);

        assertThat(result.stats().xbox()).isEqualTo(PlatformStats.of(Status.UNAVAILABLE, null));
        assertThat(result.stats().retroachievements()).isEqualTo(PlatformStats.of(Status.UNAVAILABLE, null));
        assertThat(result.stats().steam()).isNull();
    }

    @Test
    void slowXboxDoesNotBlockSteamOrOthers() {
        properties.setAggregateTimeout(Duration.ofMillis(500));
        UserAccount user = member();
        statsEverywhere(user);
        when(xboxStats.forTitle(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(slow(Duration.ofSeconds(3), Optional.of(XBOX_OK)));

        long start = System.nanoTime();
        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR",
                null, LINKS, Optional.of(user), Locale.ENGLISH);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.stats().xbox()).isNull();
        assertThat(result.stats().steam()).isEqualTo(STEAM_OK);
        assertThat(result.stats().retroachievements()).isEqualTo(RA_OK);
        assertThat(result.hltb()).isEqualTo(TTB);
        assertThat(took).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void personalStatsAlone_sameLookups() {
        UserAccount user = member();
        statsEverywhere(user);

        PersonalStatsEnrichment stats = assembler.personalStats(PORTAL, Optional.of("72"), Optional.of("620"),
                "Portal 2", user, Locale.ENGLISH);

        assertThat(stats).isEqualTo(new PersonalStatsEnrichment(STEAM_OK, XBOX_OK, RA_OK, null,
                GameBinding.SourceType.STEAM));
        verifyNoInteractions(hltb, protonDb, prices);
    }

    // ---------------------------------------------------------------- Blizzard

    private static final DetectedGame WOW = new DetectedGame("WoW", GameBinding.SourceType.BATTLENET,
            "World of Warcraft");
    private static final BlizzardStats WOW_STATS = new BlizzardStats(Status.OK, BlizzardGame.WOW, "Name#1234", "eu",
            null, null, null, null);
    private static final BlizzardPublisher WOW_PUBLIC = new BlizzardPublisher(BlizzardGame.WOW, "eu",
            new BlizzardPublisher.Wow(470_531L, null), null, null, null);

    @Test
    void publicAggregateHasNoBlizzardStats() {
        when(blizzardData.available()).thenReturn(true);
        when(blizzardData.forGame(eq(BlizzardGame.WOW), eq(BlizzardRegion.EU), any(), any()))
                .thenReturn(Optional.of(WOW_PUBLIC));

        GameEnrichment result = assembler.enrich(WOW, Optional.of("123"), Optional.empty(), "World of Warcraft",
                "FR", null, LINKS, Optional.empty(), Locale.ENGLISH);

        assertThat(result.stats()).isNull();
        verifyNoInteractions(blizzardStats);
        // the public data of the game is there, in the default region, without a member
        assertThat(result.publisher()).isEqualTo(new PublisherEnrichment(WOW_PUBLIC));
    }

    @Test
    void memberBlizzardStatsAndPublisherInTheMembersRegion() {
        UserAccount user = member();
        when(blizzardData.available()).thenReturn(true);
        when(blizzardStats.forDetected(eq(user), eq(WOW), eq("123"), eq(Locale.FRENCH), any()))
                .thenReturn(Optional.of(WOW_STATS));
        when(blizzardStats.region(user)).thenReturn(BlizzardRegion.US);
        BlizzardPublisher us = new BlizzardPublisher(BlizzardGame.WOW, "us",
                new BlizzardPublisher.Wow(200_000L, null), null, null, null);
        when(blizzardData.forGame(eq(BlizzardGame.WOW), eq(BlizzardRegion.US), any(), any()))
                .thenReturn(Optional.of(us));

        GameEnrichment result = assembler.enrich(WOW, Optional.of("123"), Optional.empty(), "World of Warcraft",
                "FR", null, LINKS, Optional.of(user), Locale.FRENCH);

        assertThat(result.stats().blizzard()).isEqualTo(WOW_STATS);
        assertThat(result.stats().detectedOn()).isEqualTo(GameBinding.SourceType.BATTLENET);
        assertThat(result.publisher().blizzard()).isEqualTo(us);
        verify(blizzardStats).forDetected(user, WOW, "123", Locale.FRENCH, FetchBudget.UNLIMITED);
    }

    @Test
    void noBlizzardGameIsNoPublisher() {
        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR",
                null, LINKS, Optional.empty(), Locale.ENGLISH);

        assertThat(result.publisher()).isNull();
        verifyNoInteractions(blizzardData);
    }

    @Test
    void slowBlizzardDoesNotBlockOthers() {
        properties.setAggregateTimeout(Duration.ofMillis(500));
        UserAccount user = member();
        statsEverywhere(user);
        when(blizzardStats.forDetected(any(), any(), any(), any(), any()))
                .thenAnswer(slow(Duration.ofSeconds(3), Optional.of(WOW_STATS)));

        long start = System.nanoTime();
        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", "FR",
                null, LINKS, Optional.of(user), Locale.ENGLISH);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.stats().blizzard()).isNull();
        assertThat(result.stats().steam()).isEqualTo(STEAM_OK);
        assertThat(result.hltb()).isEqualTo(TTB);
        assertThat(took).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void slowBlizzardStatsDoNotBlockTheBlizzardPublisher() {
        properties.setAggregateTimeout(Duration.ofMillis(500));
        UserAccount user = member();
        when(blizzardData.available()).thenReturn(true);
        when(blizzardStats.region(user)).thenReturn(BlizzardRegion.EU);
        when(blizzardData.forGame(eq(BlizzardGame.WOW), eq(BlizzardRegion.EU), any(), any()))
                .thenReturn(Optional.of(WOW_PUBLIC));
        when(blizzardStats.forDetected(any(), any(), any(), any(), any()))
                .thenAnswer(slow(Duration.ofSeconds(3), Optional.of(WOW_STATS)));

        GameEnrichment result = assembler.enrich(WOW, Optional.of("123"), Optional.empty(), "World of Warcraft",
                "FR", null, LINKS, Optional.of(user), Locale.ENGLISH);

        assertThat(result.stats()).isNull();
        assertThat(result.publisher().blizzard()).isEqualTo(WOW_PUBLIC);
    }

    @Test
    void sourceOffSkipsTheRegionRead() {
        UserAccount user = member();
        when(blizzardData.available()).thenReturn(false);

        GameEnrichment result = assembler.enrich(WOW, Optional.of("123"), Optional.empty(), "World of Warcraft",
                "FR", null, LINKS, Optional.of(user), Locale.ENGLISH);

        assertThat(result.publisher()).isNull();
        org.mockito.Mockito.verify(blizzardStats, org.mockito.Mockito.never()).region(any());
    }

    @Test
    void personalStatsAlone_includeBlizzard() {
        UserAccount user = member();
        when(blizzardStats.forDetected(eq(user), eq(WOW), eq("123"), any(), any()))
                .thenReturn(Optional.of(WOW_STATS));

        PersonalStatsEnrichment stats = assembler.personalStats(WOW, Optional.of("123"), Optional.empty(),
                "World of Warcraft", user, Locale.ENGLISH);

        assertThat(stats.blizzard()).isEqualTo(WOW_STATS);
        verifyNoInteractions(blizzardData);
    }

    private static Answer<Object> slow(Duration delay, Object value) {
        return invocation -> {
            Thread.sleep(delay.toMillis());
            return value;
        };
    }
}
