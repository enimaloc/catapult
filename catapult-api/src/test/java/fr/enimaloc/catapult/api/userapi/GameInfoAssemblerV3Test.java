package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.enrichment.GameEnrichment;
import fr.enimaloc.catapult.service.enrichment.GameEnrichmentAssembler;
import fr.enimaloc.catapult.service.enrichment.PersonalStatsEnrichment;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.tw.TwLabelService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The v3 game info: the v2 aggregation completed with the enrichment sources. */
class GameInfoAssemblerV3Test {

    private static final String BASE = "https://catapult.test";

    private final GameStateService gameStateService = mock(GameStateService.class);
    private final GameBindingRepository gameBindingRepository = mock(GameBindingRepository.class);
    private final IgdbService igdbService = mock(IgdbService.class);
    private final IgdbGameDetailsService igdbGameDetailsService = mock(IgdbGameDetailsService.class);
    private final SteamStoreService steamStoreService = mock(SteamStoreService.class);
    private final GameEnrichmentAssembler enrichmentAssembler = mock(GameEnrichmentAssembler.class);
    private final WidgetTokenService widgetTokens = mock(WidgetTokenService.class);

    private GameInfoAssembler assembler;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        assembler = assembler(Optional.of(enrichmentAssembler));
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    private GameInfoAssembler assembler(Optional<GameEnrichmentAssembler> enrichment) {
        return new GameInfoAssembler(widgetTokens, gameStateService, gameBindingRepository,
                igdbService, igdbGameDetailsService, steamStoreService, mock(XboxStoreService.class),
                mock(TwLabelService.class), null, mock(DevBackdoorResolver.class), mock(MessageSource.class),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), new PublicBaseUrl(BASE),
                enrichment);
    }

    @Test
    void nothingDetected_notInGame_noEnrichment() {
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        GameInfoV3Response response = assembler.gameInfoV3(user, Locale.ENGLISH, null, null);

        assertThat(response.inGame()).isFalse();
        assertThat(response.hltb()).isNull();
        verifyNoInteractions(enrichmentAssembler);
    }

    @Test
    void steamGame_enrichedWithItsAppIdIgdbIdAndDetectedName_currentLinks() {
        DetectedGame detected = playing(Duration.ZERO);
        ProtonDbSummary proton = new ProtonDbSummary("gold", "gold", "gold", "good", 0.7, 10, "https://p");
        when(enrichmentAssembler.enrichAsync(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
                CompletableFuture.completedFuture(new GameEnrichment(null, proton, null, null, null, null, "620", null, null, null, null, null, null)));

        GameInfoV3Response response = assembler.gameInfoV3(user, Locale.FRENCH, null, null);

        // the detected name, not the localized store name the v2 answer shows
        verify(enrichmentAssembler).enrichAsync(eq(detected), eq(Optional.of("72")), eq(Optional.of("620")),
                eq("Portal 2"), eq("FR"), isNull(), eq(new MoreLinks(BASE, GameInfoAssembler.LinkStyle.CURRENT)),
                eq(Optional.of(user)), eq(Locale.FRENCH));
        verify(igdbService, times(1)).resolveDetected(any(), any(), any());
        assertThat(response.inGame()).isTrue();
        assertThat(response.name()).isEqualTo("Portail 2");
        assertThat(response.igdb().more()).isEqualTo(BASE + "/api/games/igdb/72");
        assertThat(response.protondb().more()).isEqualTo(BASE + "/api/games/protondb/620?version=3");
        assertThat(response.protonTier()).isEqualTo("gold");
    }

    @Test
    void enrichment_overlapsTheV2Aggregation() {
        playing(Duration.ofSeconds(1));
        when(enrichmentAssembler.enrichAsync(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation ->
                CompletableFuture.supplyAsync(() -> {
                    sleep(Duration.ofSeconds(1));
                    return GameEnrichment.EMPTY;
                }));

        long start = System.nanoTime();
        GameInfoV3Response response = assembler.gameInfoV3(user, Locale.ENGLISH, null, null);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.inGame()).isTrue();
        // one after the other they would take 2 s
        assertThat(took).isLessThan(Duration.ofMillis(1700));
    }

    @Test
    void noEnrichmentAssembler_allEnrichmentNull() {
        assembler = assembler(Optional.empty());
        playing(Duration.ZERO);

        GameInfoV3Response response = assembler.gameInfoV3(user, Locale.ENGLISH, null, null);

        assertThat(response.inGame()).isTrue();
        assertThat(response.protondb()).isNull();
        assertThat(response.trailer()).isNull();
    }

    @Test
    void widgetRoutesHaveNoStats() {
        playing(Duration.ZERO);
        UUID widget = UUID.randomUUID();
        when(widgetTokens.resolve(widget)).thenReturn(Optional.of(user));
        JsonMapper mapper = JsonMapper.builder().build();

        // /api/game/{uuid} and /api/user/game/{uuid} (by widget token), and the v2 /api/me/game
        String byWidget = mapper.writeValueAsString(assembler.gameInfo(widget, Locale.ENGLISH).orElseThrow());
        String v2 = mapper.writeValueAsString(assembler.gameInfo(user, Locale.ENGLISH));

        for (String answer : List.of(byWidget, v2)) {
            assertThat(answer).contains("\"inGame\":true").doesNotContain("\"stats\"")
                    .doesNotContain("\"playtime\"").doesNotContain("\"lastAchievement\"");
        }
        verifyNoInteractions(enrichmentAssembler);
    }

    @Test
    void widgetRoutesHaveNoBlizzardStats() {
        DetectedGame wow = new DetectedGame("WoW", GameBinding.SourceType.BATTLENET, "World of Warcraft");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(wow));
        when(igdbService.resolveDetected(any(), any(), any())).thenReturn(Optional.empty());
        UUID widget = UUID.randomUUID();
        when(widgetTokens.resolve(widget)).thenReturn(Optional.of(user));
        JsonMapper mapper = JsonMapper.builder().build();

        String byWidget = mapper.writeValueAsString(assembler.gameInfo(widget, Locale.ENGLISH).orElseThrow());
        String v2 = mapper.writeValueAsString(assembler.gameInfo(user, Locale.ENGLISH));

        for (String answer : List.of(byWidget, v2)) {
            assertThat(answer).contains("\"inGame\":true").doesNotContain("\"stats\"")
                    .doesNotContain("\"blizzard\"").doesNotContain("\"publisher\"");
        }
        verifyNoInteractions(enrichmentAssembler);
    }

    @Test
    void currentStats_ofTheGamePlayedNow() {
        DetectedGame detected = playing(Duration.ZERO);
        PlatformStats steam = PlatformStats.of(Status.OK, "620");
        when(enrichmentAssembler.personalStats(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PersonalStatsEnrichment(steam, null, null, null, GameBinding.SourceType.STEAM));

        MeStatsController.CurrentStats current = assembler.currentStats(user, Locale.FRENCH);

        assertThat(current.inGame()).isTrue();
        assertThat(current.stats().steam().more()).isEqualTo(BASE + "/api/me/stats/steam/620?version=3");
        verify(enrichmentAssembler).personalStats(detected, Optional.of("72"), Optional.of("620"), "Portal 2", user,
                Locale.FRENCH);
    }

    @Test
    void currentStats_notInGameOrNoPlatform() {
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());
        assertThat(assembler.currentStats(user, Locale.ENGLISH))
                .isEqualTo(new MeStatsController.CurrentStats(false, null));
        verifyNoInteractions(enrichmentAssembler);

        playing(Duration.ZERO);
        when(enrichmentAssembler.personalStats(any(), any(), any(), any(), any(), any())).thenReturn(null);
        assertThat(assembler.currentStats(user, Locale.ENGLISH))
                .isEqualTo(new MeStatsController.CurrentStats(true, null));
        assertThat(assembler(Optional.empty()).currentStats(user, Locale.ENGLISH))
                .isEqualTo(new MeStatsController.CurrentStats(true, null));
    }

    /** Portal 2 detected on Steam, its store page (localized as "Portail 2") taking {@code storeDelay}. */
    private DetectedGame playing(Duration storeDelay) {
        DetectedGame detected = new DetectedGame("620", GameBinding.SourceType.STEAM, "Portal 2");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(igdbService.resolveDetected(eq(GameBinding.SourceType.STEAM), eq("620"), any()))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("72", "Portal 2")));
        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId("72");
        details.setSlug("portal-2");
        when(igdbGameDetailsService.getDetails("72")).thenReturn(Optional.of(details));
        SteamStoreService.SteamStorePage page = mock(SteamStoreService.SteamStorePage.class);
        when(page.name()).thenReturn("Portail 2");
        when(steamStoreService.fetchData(anyString(), any(Locale.class))).thenAnswer(invocation -> {
            sleep(storeDelay);
            return Optional.of(page);
        });
        when(steamStoreService.fetchIADisclosure(anyString(), any(Locale.class))).thenReturn(Optional.empty());
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(any(), any(), any()))
                .thenReturn(Optional.empty());
        return detected;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
