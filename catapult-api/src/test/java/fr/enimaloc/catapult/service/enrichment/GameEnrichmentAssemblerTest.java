package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.api.userapi.GameInfoAssembler;
import fr.enimaloc.catapult.api.userapi.MoreLinks;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.enrichment.hltb.HltbService;
import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckService;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbService;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The parallel, time-boxed gathering of the five enrichment sources and the trailer link. */
class GameEnrichmentAssemblerTest {

    private static final String BASE = "https://catapult.test";
    private static final MoreLinks LINKS = new MoreLinks(BASE, GameInfoAssembler.LinkStyle.CURRENT);
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
            "https://www.pcgamingwiki.com/wiki/Portal_2", "true", "true", "false", List.of());

    private final HltbService hltb = mock(HltbService.class);
    private final ProtonDbService protonDb = mock(ProtonDbService.class);
    private final SteamGridDbService steamGridDb = mock(SteamGridDbService.class);
    private final SteamDeckService steamDeck = mock(SteamDeckService.class);
    private final PcGamingWikiService pcgw = mock(PcGamingWikiService.class);
    private final IgdbService igdbService = mock(IgdbService.class);
    private final IgdbGameDetailsService igdbGameDetailsService = mock(IgdbGameDetailsService.class);
    private final SteamStoreService steamStore = mock(SteamStoreService.class);
    private final XboxStoreService xboxStore = mock(XboxStoreService.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();

    private GameEnrichmentAssembler assembler;

    @BeforeEach
    void setUp() {
        properties.setAggregateTimeout(Duration.ofSeconds(2));
        assembler = new GameEnrichmentAssembler(hltb, protonDb, steamGridDb, steamDeck, pcgw, igdbService,
                igdbGameDetailsService, steamStore, xboxStore, properties);
        when(hltb.timeToBeat(any(), any(), any())).thenReturn(Optional.of(TTB));
        when(protonDb.summary(anyString(), any())).thenReturn(Optional.of(PROTON));
        when(steamGridDb.bySteamAppId(anyString(), any())).thenReturn(Optional.of(SGDB));
        when(steamGridDb.byName(anyString(), any())).thenReturn(Optional.of(SGDB));
        when(steamDeck.compatibility(anyString(), any())).thenReturn(Optional.of(DECK));
        when(pcgw.bySteamAppId(anyString(), any())).thenReturn(Optional.of(PCGW));
        when(pcgw.byName(anyString(), any())).thenReturn(Optional.of(PCGW));
        when(steamStore.fetchData(anyString(), any(Locale.class))).thenReturn(Optional.empty());
        when(xboxStore.fetchProduct(anyString(), any())).thenReturn(Optional.empty());
        when(igdbGameDetailsService.getDetails(anyString())).thenReturn(Optional.empty());
        when(igdbService.findExternalUid(anyString(), any())).thenReturn(Optional.empty());
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
        when(hltb.timeToBeat(any(), any(), any())).thenAnswer(slow(Duration.ofMillis(700), Optional.of(TTB)));
        when(pcgw.bySteamAppId(anyString(), any())).thenAnswer(slow(Duration.ofMillis(700), Optional.of(PCGW)));
        when(steamGridDb.bySteamAppId(anyString(), any()))
                .thenAnswer(slow(Duration.ofMillis(700), Optional.of(SGDB)));

        long start = System.nanoTime();
        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", LINKS);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.hltb()).isEqualTo(TTB);
        assertThat(result.protondb()).isEqualTo(PROTON);
        assertThat(result.steamgriddb()).isEqualTo(SGDB);
        assertThat(result.steamDeck()).isEqualTo(DECK);
        assertThat(result.pcgamingwiki()).isEqualTo(PCGW);
        assertThat(result.steamAppId()).isEqualTo("620");
        // five 700 ms calls one after the other would take 3.5 s
        assertThat(took).isLessThan(Duration.ofMillis(1800));
        verify(hltb).timeToBeat(eq("72"), eq("Portal 2"), eq(FetchBudget.UNLIMITED));
        verify(protonDb).summary(eq("620"), eq(FetchBudget.UNLIMITED));
        verify(steamGridDb, never()).byName(anyString(), any());
        verify(pcgw, never()).byName(anyString(), any());
        verify(igdbService, never()).findExternalUid(anyString(), any());
    }

    @Test
    void slowSource_nullAfterTimeout_othersPresent() {
        when(protonDb.summary(anyString(), any())).thenAnswer(slow(Duration.ofSeconds(5), Optional.of(PROTON)));

        long start = System.nanoTime();
        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", LINKS);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.protondb()).isNull();
        assertThat(result.hltb()).isEqualTo(TTB);
        assertThat(result.steamgriddb()).isEqualTo(SGDB);
        assertThat(result.steamDeck()).isEqualTo(DECK);
        assertThat(result.pcgamingwiki()).isEqualTo(PCGW);
        assertThat(took).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void failingSource_null_noException() {
        when(steamDeck.compatibility(anyString(), any())).thenThrow(new IllegalStateException("boom"));
        when(hltb.timeToBeat(any(), any(), any()))
                .thenThrow(new UpstreamBudgetExceededException(Duration.ofMinutes(1)));

        GameEnrichment result = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", LINKS);

        assertThat(result.steamDeck()).isNull();
        assertThat(result.hltb()).isNull();
        assertThat(result.protondb()).isEqualTo(PROTON);
        assertThat(result.pcgamingwiki()).isEqualTo(PCGW);
    }

    @Test
    void aggregate_nonSteamWithoutAppId_namesOnly() {
        GameEnrichment result = assembler.enrich(HALO, Optional.empty(), Optional.empty(), "Halo", LINKS);

        assertThat(result.protondb()).isNull();
        assertThat(result.steamDeck()).isNull();
        assertThat(result.steamAppId()).isNull();
        assertThat(result.steamgriddb()).isEqualTo(SGDB);
        assertThat(result.pcgamingwiki()).isEqualTo(PCGW);
        verify(steamGridDb).byName(eq("Halo"), eq(FetchBudget.UNLIMITED));
        verify(pcgw).byName(eq("Halo"), eq(FetchBudget.UNLIMITED));
        verify(protonDb, never()).summary(anyString(), any());
        verify(steamDeck, never()).compatibility(anyString(), any());
        verify(hltb).timeToBeat(eq(null), eq("Halo"), eq(FetchBudget.UNLIMITED));
    }

    @Test
    void xboxGameWithIgdbSteamMapping_usesResolvedAppId() {
        when(igdbService.findExternalUid("1234", GameBinding.SourceType.STEAM)).thenReturn(Optional.of("976730"));

        GameEnrichment result = assembler.enrich(HALO, Optional.of("1234"), Optional.empty(), "Halo", LINKS);

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

        // Steam: the highlight movie's HLS H.264 stream wins
        SteamStoreService.SteamStorePage page = mock(SteamStoreService.SteamStorePage.class);
        when(page.movies()).thenReturn(new SteamStoreService.SteamStorePage.Movie[]{
                new SteamStoreService.SteamStorePage.Movie(1, "teaser", null, null, null, null, false),
                new SteamStoreService.SteamStorePage.Movie(2, "launch", null, null, null,
                        "https://video.steam/hls/2/master.m3u8", true)});
        when(steamStore.fetchData(eq("620"), any(Locale.class))).thenReturn(Optional.of(page));
        GameEnrichment steam = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", LINKS);
        assertThat(steam.trailer()).isEqualTo(new GameEnrichment.Trailer("mp4", BASE + "/api/me/game/trailer.mp4"));

        // Xbox: the first video's HLS stream
        when(xboxStore.fetchProduct(eq(HALO.getSourceId()), any())).thenReturn(Optional.of(xboxProduct(
                List.of(new XboxStoreService.XboxVideo("trailer", null, "https://xbox/hls.m3u8", null, 0, 0)))));
        GameEnrichment xbox = assembler.enrich(HALO, Optional.of("72"), Optional.empty(), "Halo", LINKS);
        assertThat(xbox.trailer()).isEqualTo(new GameEnrichment.Trailer("mp4", BASE + "/api/me/game/trailer.mp4"));

        // no HLS anywhere: IGDB's first video, on YouTube
        when(page.movies()).thenReturn(new SteamStoreService.SteamStorePage.Movie[]{
                new SteamStoreService.SteamStorePage.Movie(2, "launch", null, "dash", "dash", " ", true)});
        GameEnrichment youtube = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", LINKS);
        assertThat(youtube.trailer())
                .isEqualTo(new GameEnrichment.Trailer("youtube", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"));

        // trailer source switched off: never the MP4 route
        properties.getTrailer().setEnabled(false);
        when(page.movies()).thenReturn(new SteamStoreService.SteamStorePage.Movie[]{
                new SteamStoreService.SteamStorePage.Movie(2, "launch", null, null, null, "https://hls", true)});
        GameEnrichment off = assembler.enrich(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2", LINKS);
        assertThat(off.trailer().kind()).isEqualTo("youtube");

        // nothing at all
        GameEnrichment none = assembler.enrich(PORTAL, Optional.empty(), Optional.of("620"), "Portal 2", LINKS);
        assertThat(none.trailer()).isNull();
    }

    @Test
    void enrichAsync_completesWithTheEnrichment() {
        GameEnrichment result = assembler.enrichAsync(PORTAL, Optional.of("72"), Optional.of("620"), "Portal 2",
                LINKS).join();

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
                .withBean(IgdbService.class, () -> igdbService)
                .withBean(IgdbGameDetailsService.class, () -> igdbGameDetailsService)
                .withBean(SteamStoreService.class, () -> steamStore)
                .withBean(XboxStoreService.class, () -> xboxStore)
                .withBean(EnrichmentProperties.class)
                .withUserConfiguration(GameEnrichmentAssembler.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(GameEnrichmentAssembler.class));
    }

    private static XboxStoreService.XboxProduct xboxProduct(List<XboxStoreService.XboxVideo> videos) {
        return new XboxStoreService.XboxProduct("Halo", null, null, null, null, null, null, null, null, List.of(),
                videos, List.of(), List.of(), null);
    }

    private static Answer<Object> slow(Duration delay, Object value) {
        return invocation -> {
            Thread.sleep(delay.toMillis());
            return value;
        };
    }
}
