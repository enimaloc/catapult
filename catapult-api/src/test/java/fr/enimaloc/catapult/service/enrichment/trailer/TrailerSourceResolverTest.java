package fr.enimaloc.catapult.service.enrichment.trailer;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which upstream HLS stream a trailer is remuxed from, and that only Steam's and Microsoft's CDNs are trusted. */
class TrailerSourceResolverTest {

    private static final String STEAM_HLS =
            "https://video.akamai.steamstatic.com/store_trailers/620/2/abc/1750650443/hls_264_master.m3u8?t=1";
    private static final String XBOX_HLS =
            "https://cdn-dynmedia-1.microsoft.com/is/content/microsoftassets/c3b48e9b-AVS.m3u8?packagedStreaming=true";

    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final SteamStoreService steamStore = mock(SteamStoreService.class);
    private final XboxStoreService xboxStore = mock(XboxStoreService.class);
    private final IgdbService igdbService = mock(IgdbService.class);
    private final GameStateService gameStateService = mock(GameStateService.class);

    private TrailerSourceResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resolver = new TrailerSourceResolver(cache, steamStore, xboxStore, igdbService, gameStateService);
        // a pass-through cache: every lookup fetches
        when(cache.get(eq(EnrichmentSource.TRAILER), anyString(), eq(String.class), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Optional<String>>) invocation.getArgument(4)).get());
        when(steamStore.fetchData(anyString(), any(Locale.class), anyBoolean())).thenReturn(Optional.empty());
        when(xboxStore.fetchProduct(anyString(), any())).thenReturn(Optional.empty());
        when(igdbService.resolveDetected(any(), any(), any())).thenReturn(Optional.empty());
        when(igdbService.findExternalUid(anyString(), any())).thenReturn(Optional.empty());
    }

    private void steamMovies(String appId, SteamStoreService.SteamStorePage.Movie... movies) {
        SteamStoreService.SteamStorePage page = mock(SteamStoreService.SteamStorePage.class);
        when(page.movies()).thenReturn(movies);
        when(steamStore.fetchData(eq(appId), any(Locale.class), anyBoolean())).thenReturn(Optional.of(page));
    }

    private void xboxVideos(String productId, String... hlsUrls) {
        List<XboxStoreService.XboxVideo> videos = java.util.Arrays.stream(hlsUrls)
                .map(hls -> new XboxStoreService.XboxVideo("trailer", "https://dash", hls, null, 0, 0))
                .toList();
        when(xboxStore.fetchProduct(eq(productId), any())).thenReturn(Optional.of(
                new XboxStoreService.XboxProduct("Halo", null, null, null, null, null, null, null, null, List.of(),
                        videos, List.of(), List.of(), null)));
    }

    private static SteamStoreService.SteamStorePage.Movie movie(int id, String hls, boolean highlight) {
        return new SteamStoreService.SteamStorePage.Movie(id, "movie " + id, null, "https://av1.mpd",
                "https://h264.mpd", hls, highlight);
    }

    @Test
    void steam_prefersHighlightHlsH264() {
        steamMovies("620", movie(1, STEAM_HLS.replace("/2/", "/1/"), false), movie(2, STEAM_HLS, true));

        assertThat(resolver.steam("620", FetchBudget.UNLIMITED)).contains(URI.create(STEAM_HLS));
        verify(cache).get(eq(EnrichmentSource.TRAILER), eq("steam:620"), eq(String.class), eq(FetchBudget.UNLIMITED),
                any());
    }

    @Test
    void steam_withoutHighlight_takesTheFirstMovie() {
        steamMovies("620", movie(1, STEAM_HLS, false), movie(2, STEAM_HLS.replace("/2/", "/3/"), false));

        assertThat(resolver.steam("620", FetchBudget.UNLIMITED)).contains(URI.create(STEAM_HLS));
    }

    @Test
    void steam_onlyDashAv1_empty() {
        steamMovies("620", movie(1, null, true), movie(2, " ", false));

        assertThat(resolver.steam("620", FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void xbox_firstVideoHls() {
        xboxVideos("9NBLGGH4R315", XBOX_HLS, XBOX_HLS.replace("c3b48e9b", "1c705f71"));

        assertThat(resolver.xbox("9NBLGGH4R315", FetchBudget.UNLIMITED)).contains(URI.create(XBOX_HLS));
        verify(cache).get(eq(EnrichmentSource.TRAILER), eq("xbox:9NBLGGH4R315"), eq(String.class), any(), any());
    }

    @Test
    void xbox_noVideo_empty() {
        xboxVideos("9NBLGGH4R315");

        assertThat(resolver.xbox("9NBLGGH4R315", FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void foreignHost_neverHandedOver() {
        steamMovies("1", movie(1, "https://evil.example/hls.m3u8", true));
        steamMovies("2", movie(1, "file:///etc/passwd", true));
        steamMovies("3", movie(1, "http://video.akamai.steamstatic.com/hls.m3u8", true));
        steamMovies("4", movie(1, "https://video.akamai.steamstatic.com.evil.example/hls.m3u8", true));
        steamMovies("5", movie(1, "https://user@video.akamai.steamstatic.com/hls.m3u8", true));
        steamMovies("6", movie(1, "concat:https://video.akamai.steamstatic.com/a|https://evil/b", true));
        xboxVideos("X", "https://cdn-dynmedia-1.microsoft.com.evil.example/x.m3u8");

        for (String appId : List.of("1", "2", "3", "4", "5", "6")) {
            assertThat(resolver.steam(appId, FetchBudget.UNLIMITED)).as(appId).isEmpty();
        }
        assertThat(resolver.xbox("X", FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void cachedForeignHost_stillRefused() {
        when(cache.get(eq(EnrichmentSource.TRAILER), eq("steam:7"), eq(String.class), any(), any()))
                .thenReturn(Optional.of("https://169.254.169.254/latest/meta-data"));

        assertThat(resolver.steam("7", FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void resolve_steamFirst_thenXbox() {
        steamMovies("620", movie(1, STEAM_HLS, true));
        xboxVideos("9N", XBOX_HLS);

        assertThat(resolver.resolve("620", "9N", FetchBudget.UNLIMITED)).contains(URI.create(STEAM_HLS));
        verify(xboxStore, never()).fetchProduct(anyString(), any());

        assertThat(resolver.resolve("404", "9N", FetchBudget.UNLIMITED)).contains(URI.create(XBOX_HLS));
        assertThat(resolver.resolve(null, "9N", FetchBudget.UNLIMITED)).contains(URI.create(XBOX_HLS));
        assertThat(resolver.resolve(null, null, FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void current_steamGame_usesTheDetectedAppId() {
        UserAccount user = new UserAccount();
        when(gameStateService.getLastKnownGame(user))
                .thenReturn(Optional.of(new DetectedGame("620", GameBinding.SourceType.STEAM, "Portal 2")));
        steamMovies("620", movie(1, STEAM_HLS, true));

        assertThat(resolver.current(user)).contains(URI.create(STEAM_HLS));
        verify(igdbService, never()).findExternalUid(anyString(), any());
    }

    @Test
    void current_xboxGame_triesIgdbsSteamMappingFirst_thenTheXboxProduct() {
        UserAccount user = new UserAccount();
        DetectedGame halo = new DetectedGame("9N", GameBinding.SourceType.XBOX, "Halo");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(halo));
        when(igdbService.resolveDetected(GameBinding.SourceType.XBOX, "9N", "Halo"))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("72", "Halo")));
        when(igdbService.findExternalUid("72", GameBinding.SourceType.STEAM)).thenReturn(Optional.of("976730"));
        xboxVideos("9N", XBOX_HLS);

        // the Steam mapping has no HLS: the Xbox product's
        assertThat(resolver.current(user)).contains(URI.create(XBOX_HLS));
        verify(steamStore).fetchData(eq("976730"), any(Locale.class), anyBoolean());

        steamMovies("976730", movie(1, STEAM_HLS, true));
        assertThat(resolver.current(user)).contains(URI.create(STEAM_HLS));
    }

    @Test
    void current_noGame_empty() {
        UserAccount user = new UserAccount();
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        assertThat(resolver.current(user)).isEmpty();
    }

    @Test
    void wiresInASpringContext() {
        new ApplicationContextRunner()
                // IgdbService declares @DependsOn("flyway")
                .withBean("flyway", Object.class, Object::new)
                .withBean(EnrichmentCache.class, () -> cache)
                .withBean(SteamStoreService.class, () -> steamStore)
                .withBean(XboxStoreService.class, () -> xboxStore)
                .withBean(IgdbService.class, () -> igdbService)
                .withBean(GameStateService.class, () -> gameStateService)
                .withUserConfiguration(TrailerSourceResolver.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(TrailerSourceResolver.class));
    }
}
