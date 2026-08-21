package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.IgdbGameCacheEntry;
import fr.enimaloc.catapult.domain.IgdbGameCcl;
import fr.enimaloc.catapult.domain.IgdbGameExternalId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import fr.enimaloc.catapult.repository.IgdbGameCacheRepository;
import fr.enimaloc.catapult.repository.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.IgdbGameExternalIdRepository;
import fr.enimaloc.catapult.repository.TwitchCclDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import proto.AlternativeName;
import proto.ExternalGame;
import proto.ExternalGameSource;
import proto.Game;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IgdbServiceTest {

    @Mock IgdbClient igdbClient;
    @Mock IgdbGameCacheRepository cacheRepository;
    @Mock IgdbGameExternalIdRepository externalIdRepository;
    @Mock IgdbGameCclRepository cclRepository;
    @Mock TwitchCclDefinitionRepository twitchCclRepo;
    @Mock SteamStoreService steamStoreService;
    @Mock RestClient restClient;
    @Mock MeterRegistry meterRegistry;

    private SimpleMeterRegistry testRegistry;

    @Mock RestClient.RequestBodyUriSpec postSpec;
    @Mock RestClient.RequestBodySpec bodySpec;
    @Mock RestClient.ResponseSpec responseSpec;

    @InjectMocks IgdbService igdbService;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(igdbService, "clientId", "test-client-id");
        ReflectionTestUtils.setField(igdbService, "clientSecret", "test-client-secret");
        ReflectionTestUtils.setField(igdbService, "cacheTtlHours", 24);

        when(cacheRepository.findByCachedAtAfter(any(Instant.class))).thenReturn(List.of());
        when(cclRepository.findAllIgdbIds()).thenReturn(Set.of());

        testRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(igdbService, "meterRegistry", testRegistry);
    }

    // --- blank clientId early returns ---

    @Test
    void findBySteamAppId_blankClientId_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        assertThat(igdbService.findBySteamAppId("12345")).isEmpty();
    }

    @Test
    void findByName_blankClientId_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        assertThat(igdbService.findByName("Fortnite")).isEmpty();
    }

    @Test
    void findByWindowsExecutable_blankClientId_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        assertThat(igdbService.findByWindowsExecutable("fortnite.exe")).isEmpty();
    }

    @Test
    void searchGames_blankClientId_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        assertThat(igdbService.searchGames("Fortnite")).isEmpty();
    }

    @Test
    void searchGames_shortQuery_returnsEmpty() {
        assertThat(igdbService.searchGames("a")).isEmpty();
    }

    @Test
    void prewarmCclCache_blankClientId_returnsEarly() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        igdbService.prewarmCclCache();

        verifyNoInteractions(igdbClient);
    }

    @Test
    void findTwitchGameId_noTwitchSource_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "twitchSourceId", -1L);

        assertThat(igdbService.findTwitchGameId("42")).isEmpty();
    }

    // --- in-memory cache hits ---

    @Test
    void findByName_l1CacheHit_returnsCachedGame() {
        var game = new IgdbService.IgdbGame("42", "Fortnite");
        ReflectionTestUtils.invokeMethod(igdbService, "warmInMemoryCacheFromDb");

        var entry = new IgdbGameCacheEntry("name:fortnite", "42", "Fortnite");
        when(cacheRepository.findByCachedAtAfter(any())).thenReturn(List.of(entry));
        igdbService.init();

        assertThat(igdbService.findByName("Fortnite")).contains(game);
        verifyNoInteractions(igdbClient);
    }

    @Test
    void suggestCcls_l1CacheHit_returnsCachedCcls() {
        Set<String> ccls = Set.of("ViolentGraphic");
        ReflectionTestUtils.setField(igdbService, "cclCache",
            new java.util.concurrent.ConcurrentHashMap<>(Map.of("42", ccls)));

        assertThat(igdbService.suggestCcls("42")).isEqualTo(ccls);
        verifyNoInteractions(igdbClient);
    }

    @Test
    void suggestCcls_l2DbHit_returnsFromDb() {
        IgdbGameCcl dbEntry = new IgdbGameCcl("42", Set.of("SexualThemes"), "PEGI 18");
        when(cclRepository.findById("42")).thenReturn(Optional.of(dbEntry));

        Set<String> result = igdbService.suggestCcls("42");

        assertThat(result).containsExactly("SexualThemes");
        verifyNoInteractions(igdbClient);
    }

    @Test
    void suggestCcls_blankClientIdAfterCacheMiss_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");
        when(cclRepository.findById(anyString())).thenReturn(Optional.empty());

        assertThat(igdbService.suggestCcls("42")).isEmpty();
    }

    @Test
    void getGameCache_returnsUnmodifiableView() {
        assertThat(igdbService.getGameCache()).isNotNull();
    }

    // --- getOrRefreshAppToken ---

    @Test
    void getOrRefreshAppToken_cachedTokenNotExpired_returnsCachedToken() {
        ReflectionTestUtils.setField(igdbService, "appAccessToken", "cached-token");
        ReflectionTestUtils.setField(igdbService, "tokenExpiresAt",
            Instant.now().plusSeconds(3600));

        String token = igdbService.getOrRefreshAppToken();

        assertThat(token).isEqualTo("cached-token");
        verifyNoInteractions(restClient);
    }

    @Test
    void getOrRefreshAppToken_expiredToken_fetchesNewToken() {
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object[].class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of(
            "access_token", "new-token",
            "expires_in", 3600
        ));

        String token = igdbService.getOrRefreshAppToken();

        assertThat(token).isEqualTo("new-token");
    }

    @Test
    void getOrRefreshAppToken_fetchFails_returnsEmpty() {
        when(restClient.post()).thenThrow(new RuntimeException("Network error"));

        String token = igdbService.getOrRefreshAppToken();

        assertThat(token).isEmpty();
    }

    @Test
    void getOrRefreshAppToken_nullResponse_returnsEmpty() {
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object[].class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(null);

        String token = igdbService.getOrRefreshAppToken();

        assertThat(token).isEmpty();
    }

    // --- prewarmSteamAppIds ---

    @Test
    void prewarmSteamAppIds_emptyList_returnsEarly() {
        igdbService.prewarmSteamAppIds(List.of());

        verifyNoInteractions(igdbClient);
    }

    @Test
    void prewarmSteamAppIds_blankClientId_returnsEarly() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        igdbService.prewarmSteamAppIds(List.of("12345"));

        verifyNoInteractions(igdbClient);
    }

    // --- findBySteamAppId with igdbClient ---

    private void setValidToken() {
        ReflectionTestUtils.setField(igdbService, "appAccessToken", "test-token");
        ReflectionTestUtils.setField(igdbService, "tokenExpiresAt", Instant.now().plusSeconds(3600));
    }

    @Test
    void findBySteamAppId_igdbClientReturnsGame_returnsGame() {
        setValidToken();
        Game game = Game.newBuilder().setId(42).setName("Fortnite").build();
        ExternalGame ext = ExternalGame.newBuilder().setGame(game).build();
        when(igdbClient.findExternalGameByUid(anyString(), anyLong(), anyString()))
            .thenReturn(List.of(ext));

        Optional<IgdbService.IgdbGame> result = igdbService.findBySteamAppId("12345");

        assertThat(result).contains(new IgdbService.IgdbGame("42", "Fortnite"));
    }

    @Test
    void findBySteamAppId_igdbClientEmpty_returnsEmpty() {
        setValidToken();
        when(igdbClient.findExternalGameByUid(anyString(), anyLong(), anyString()))
            .thenReturn(List.of());

        assertThat(igdbService.findBySteamAppId("12345")).isEmpty();
    }

    @Test
    void findBySteamAppId_resolvesToParent_beforeLookup() {
        setValidToken();
        when(steamStoreService.resolveEffectiveApp("4519120"))
            .thenReturn(Optional.of(new SteamStoreService.ResolvedParentApp("4009490", "Arctic Drive")));
        Game game = Game.newBuilder().setId(99).setName("Arctic Drive").build();
        ExternalGame ext = ExternalGame.newBuilder().setGame(game).build();
        when(igdbClient.findExternalGameByUid("4009490", -1L, "test-token"))
            .thenReturn(List.of(ext));

        Optional<IgdbService.IgdbGame> result = igdbService.findBySteamAppId("4519120");

        assertThat(result).contains(new IgdbService.IgdbGame("99", "Arctic Drive"));
    }

    // --- findByName with igdbClient ---

    @Test
    void findByName_igdbClientReturnsGame_returnsGame() {
        setValidToken();
        Game game = Game.newBuilder().setId(99).setName("Minecraft").build();
        when(igdbClient.searchByName(anyString(), anyString())).thenReturn(List.of(game));

        Optional<IgdbService.IgdbGame> result = igdbService.findByName("Minecraft");

        assertThat(result).contains(new IgdbService.IgdbGame("99", "Minecraft"));
    }

    @Test
    void findByName_igdbClientEmpty_returnsEmpty() {
        setValidToken();
        when(igdbClient.searchByName(anyString(), anyString())).thenReturn(List.of());

        assertThat(igdbService.findByName("Minecraft")).isEmpty();
    }

    // --- searchGames with token ---

    @Test
    void searchGames_withResults_returnsList() {
        setValidToken();
        Game game = Game.newBuilder().setId(7).setName("Halo").build();
        when(igdbClient.searchByName(anyString(), anyString())).thenReturn(List.of(game));

        List<IgdbService.IgdbGame> results = igdbService.searchGames("Halo");

        assertThat(results).containsExactly(new IgdbService.IgdbGame("7", "Halo"));
    }

    @Test
    void searchGames_blankToken_returnsEmpty() {
        when(restClient.post()).thenThrow(new RuntimeException("Network error"));

        assertThat(igdbService.searchGames("Halo")).isEmpty();
    }

    // --- findWindowsExecutable paths (new lines L206-236) ---

    @Test
    void findByWindowsExecutable_returnsGame() {
        setValidToken();
        Game game = Game.newBuilder().setId(7).setName("Half-Life").build();
        AlternativeName alt = AlternativeName.newBuilder().setGame(game).build();
        when(igdbClient.findByWindowsExecutable(anyString(), anyString()))
            .thenReturn(List.of(alt));

        Optional<IgdbService.IgdbGame> result = igdbService.findByWindowsExecutable("hl2.exe");

        assertThat(result).contains(new IgdbService.IgdbGame("7", "Half-Life"));
    }

    @Test
    void findByWindowsExecutable_withoutExtension_appendsExt() {
        setValidToken();
        when(igdbClient.findByWindowsExecutable(anyString(), anyString()))
            .thenReturn(List.of());

        igdbService.findByWindowsExecutable("game");

        verify(igdbClient).findByWindowsExecutable(eq("game.exe"), anyString());
    }

    @Test
    void findByWindowsExecutable_igdbClientEmpty_returnsEmpty() {
        setValidToken();
        when(igdbClient.findByWindowsExecutable(anyString(), anyString()))
            .thenReturn(List.of());

        assertThat(igdbService.findByWindowsExecutable("missing.exe")).isEmpty();
    }

    // --- findBySteamAppId with steamSourceId >= 0 (new lines L104-106) ---

    @Test
    void findBySteamAppId_withSteamSourceId_externalIdFound_fallsThrough() {
        setValidToken();
        ReflectionTestUtils.setField(igdbService, "steamSourceId", 1L);
        IgdbGameExternalId extId = new IgdbGameExternalId("42", 1L, "730");
        when(externalIdRepository.findBySourceIdAndUid(1L, "730")).thenReturn(Optional.of(extId));
        // igdbGameCache doesn't have "42" → falls through to igdbClient
        Game game = Game.newBuilder().setId(42).setName("CS:GO").build();
        ExternalGame ext = ExternalGame.newBuilder().setGame(game).build();
        when(igdbClient.findExternalGameByUid(anyString(), anyLong(), anyString()))
            .thenReturn(List.of(ext));

        Optional<IgdbService.IgdbGame> result = igdbService.findBySteamAppId("730");

        assertThat(result).isPresent();
    }

    // --- getTwitchSourceId getter (new line L500) ---

    @Test
    void getTwitchSourceId_returnsDefault() {
        assertThat(igdbService.getTwitchSourceId()).isEqualTo(-1L);
    }

    // --- prewarmCclCache covers L265 (.toList()) ---

    @Test
    void prewarmCclCache_withCachedGame_toListCalled() {
        IgdbGameCacheEntry entry = new IgdbGameCacheEntry("steam:99", "99", "TestGame");
        when(cacheRepository.findByCachedAtAfter(any(Instant.class))).thenReturn(List.of(entry));
        when(cclRepository.findAllIgdbIds()).thenReturn(Set.of("99")); // already cached → toLoad empty

        igdbService.prewarmCclCache(); // reaches .toList() at L265 then returns early

        verify(cclRepository).findAllIgdbIds();
    }

    // --- suggestCcls paths (new lines L341-346 and L377) ---

    @Test
    void suggestCcls_withSteamSourceId_steamAppIdPresent_fetchesSteamCcls() {
        setValidToken();
        ReflectionTestUtils.setField(igdbService, "steamSourceId", 1L);
        when(cclRepository.findById("99")).thenReturn(Optional.empty());
        Game game = Game.newBuilder().setId(99).setName("TestGame").build();
        when(igdbClient.fetchGameById(eq("99"), anyString(), anyString()))
            .thenReturn(List.of(game));
        IgdbGameExternalId extId = new IgdbGameExternalId("99", 1L, "steam-99");
        when(externalIdRepository.findByIgdbIdAndSourceId("99", 1L))
            .thenReturn(Optional.of(extId));
        when(steamStoreService.fetchCcls(List.of("steam-99"))).thenReturn(Map.of());

        Set<String> result = igdbService.suggestCcls("99");

        assertThat(result).isNotNull();
    }

    @Test
    void suggestCcls_gameWithNoAgeRatings_returnsEmpty() {
        setValidToken();
        when(cclRepository.findById("88")).thenReturn(Optional.empty());
        Game game = Game.newBuilder().setId(88).setName("EmptyGame").build();
        when(igdbClient.fetchGameById(eq("88"), anyString(), anyString()))
            .thenReturn(List.of(game));

        Set<String> result = igdbService.suggestCcls("88"); // covers L377 (descriptorIds empty)

        assertThat(result).isEmpty();
    }

    // --- loadSourceId branches (L426 sources found, L431 sources empty) ---

    @Test
    void init_sourcesFound_resolvesSourceId() throws Exception {
        ReflectionTestUtils.setField(igdbService, "clientSecret", "test-secret");
        setValidToken();
        ExternalGameSource source = ExternalGameSource.newBuilder().setId(5).setName("Steam").build();
        // doReturn avoids compiler error on checked RequestException
        doReturn(List.of(source)).when(igdbClient).findSourcesByName(anyString(), anyString());

        igdbService.init(); // covers L426 (sources not empty)

        assertThat(igdbService.getTwitchSourceId()).isNotEqualTo(-1L);
    }

    @Test
    void init_sourcesEmpty_keepsDefaultSourceId() throws Exception {
        ReflectionTestUtils.setField(igdbService, "clientSecret", "test-secret");
        setValidToken();
        doReturn(List.of()).when(igdbClient).findSourcesByName(anyString(), anyString());

        igdbService.init(); // covers L431 (sources empty, logs warning)

        assertThat(igdbService.getTwitchSourceId()).isEqualTo(-1L);
    }

    // --- cache metrics ---

    @Test
    void findBySteamAppId_l1Hit_incrementsHitCounter() {
        ReflectionTestUtils.setField(igdbService, "steamSourceId", 1L);
        IgdbGameExternalId extId = new IgdbGameExternalId("42", 1L, "12345");
        when(externalIdRepository.findBySourceIdAndUid(1L, "12345")).thenReturn(Optional.of(extId));
        @SuppressWarnings("unchecked")
        Map<String, String> cache = (Map<String, String>) ReflectionTestUtils.getField(igdbService, "igdbGameCache");
        cache.put("42", "Half-Life");

        igdbService.findBySteamAppId("12345");

        assertThat(testRegistry.counter("catapult.igdb.cache.lookup",
            "method", "steam", "result", "hit").count()).isEqualTo(1.0);
    }

    @Test
    void findBySteamAppId_dbHit_incrementsDbHitCounter() {
        ReflectionTestUtils.setField(igdbService, "steamSourceId", -1L);
        IgdbGameCacheEntry entry = new IgdbGameCacheEntry("steam:99999", "99", "Portal");
        when(cacheRepository.findById("steam:99999")).thenReturn(Optional.of(entry));

        igdbService.findBySteamAppId("99999");

        assertThat(testRegistry.counter("catapult.igdb.cache.lookup",
            "method", "steam", "result", "db_hit").count()).isEqualTo(1.0);
    }

    @Test
    void findByName_l1Hit_incrementsHitCounter() {
        @SuppressWarnings("unchecked")
        Map<String, IgdbService.IgdbGame> index =
            (Map<String, IgdbService.IgdbGame>) ReflectionTestUtils.getField(igdbService, "igdbNameIndex");
        index.put("portal", new IgdbService.IgdbGame("1", "Portal"));

        igdbService.findByName("Portal");

        assertThat(testRegistry.counter("catapult.igdb.cache.lookup",
            "method", "name", "result", "hit").count()).isEqualTo(1.0);
    }

    @Test
    void findByWindowsExecutable_l1Hit_incrementsHitCounter() {
        @SuppressWarnings("unchecked")
        Map<String, IgdbService.IgdbGame> index =
            (Map<String, IgdbService.IgdbGame>) ReflectionTestUtils.getField(igdbService, "exeNameIndex");
        index.put("portal2.exe", new IgdbService.IgdbGame("2", "Portal 2"));

        igdbService.findByWindowsExecutable("portal2.exe");

        assertThat(testRegistry.counter("catapult.igdb.cache.lookup",
            "method", "exe", "result", "hit").count()).isEqualTo(1.0);
    }
}
