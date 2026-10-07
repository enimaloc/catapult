package fr.enimaloc.catapult.service;

import com.api.igdb.exceptions.RequestException;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameCacheEntry;
import fr.enimaloc.catapult.domain.IgdbGameCcl;
import fr.enimaloc.catapult.domain.IgdbGameExternalId;
import fr.enimaloc.catapult.domain.IgdbRatingDescriptor;
import fr.enimaloc.catapult.domain.TwitchCclDefinition;
import fr.enimaloc.catapult.repository.IgdbGameCacheRepository;
import fr.enimaloc.catapult.repository.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.IgdbGameExternalIdRepository;
import fr.enimaloc.catapult.repository.TwitchCclDefinitionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import proto.AgeRating;
import proto.AgeRatingCategory;
import proto.AgeRatingContentDescriptionV2;
import proto.AgeRatingOrganization;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** IgdbService's lookup chains (L1 → external ids → DB → IGDB), prewarming and CCL suggestions. */
class IgdbServiceFlowsTest {

    private static final long STEAM = 1;
    private static final long XBOX = 11;
    private static final long TWITCH = 14;

    private final IgdbClient igdb = mock(IgdbClient.class);
    private final IgdbGameCacheRepository cache = mock(IgdbGameCacheRepository.class);
    private final IgdbGameExternalIdRepository externalIds = mock(IgdbGameExternalIdRepository.class);
    private final IgdbGameCclRepository ccls = mock(IgdbGameCclRepository.class);
    private final TwitchCclDefinitionRepository cclDefinitions = mock(TwitchCclDefinitionRepository.class);
    private final SteamStoreService steamStore = mock(SteamStoreService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final IgdbService service = new IgdbService(igdb, cache, externalIds, ccls, cclDefinitions, steamStore,
            mock(RestClient.class), meters);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "clientId", "client");
        ReflectionTestUtils.setField(service, "clientSecret", "secret");
        ReflectionTestUtils.setField(service, "cacheTtlHours", 24);
        ReflectionTestUtils.setField(service, "steamSourceId", STEAM);
        ReflectionTestUtils.setField(service, "xboxSourceId", XBOX);
        ReflectionTestUtils.setField(service, "twitchSourceId", TWITCH);
        validToken();
        when(cache.findById(anyString())).thenReturn(Optional.empty());
        when(externalIds.findBySourceIdAndUid(anyLong(), anyString())).thenReturn(Optional.empty());
        when(externalIds.findByIgdbIdAndSourceId(anyString(), anyLong())).thenReturn(Optional.empty());
        when(ccls.findById(anyString())).thenReturn(Optional.empty());
        when(steamStore.resolveEffectiveApp(anyString())).thenReturn(Optional.empty());
        when(steamStore.fetchCcls(anyCollection())).thenReturn(Map.of());
    }

    private void validToken() {
        ReflectionTestUtils.setField(service, "appAccessToken", "token");
        ReflectionTestUtils.setField(service, "tokenExpiresAt", Instant.now().plusSeconds(3600));
    }

    /** The mocked RestClient fails every token request, so a missing token stays blank. */
    private void noToken() {
        ReflectionTestUtils.setField(service, "appAccessToken", null);
        ReflectionTestUtils.setField(service, "tokenExpiresAt", Instant.EPOCH);
    }

    private void blankClientId() {
        ReflectionTestUtils.setField(service, "clientId", "");
    }

    private double lookups(String method, String result) {
        var counter = meters.find("catapult.igdb.cache.lookup").tag("method", method).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private void cached(String key, String igdbId, String name) {
        when(cache.findById(key)).thenReturn(Optional.of(new IgdbGameCacheEntry(key, igdbId, name)));
    }

    private static Game game(long id, String name) {
        return Game.newBuilder().setId(id).setName(name).build();
    }

    private static ExternalGame external(String uid, long id, String name) {
        return ExternalGame.newBuilder().setUid(uid).setGame(game(id, name)).build();
    }

    private static AgeRating rating(String org, String value, AgeRatingContentDescriptionV2... descriptors) {
        return AgeRating.newBuilder()
                .setRatingCategory(AgeRatingCategory.newBuilder()
                        .setOrganization(AgeRatingOrganization.newBuilder().setName(org)).setRating(value))
                .addAllRatingContentDescriptions(List.of(descriptors))
                .build();
    }

    private static AgeRatingContentDescriptionV2 descriptor(long id, String description) {
        return AgeRatingContentDescriptionV2.newBuilder().setId(id).setDescription(description).build();
    }

    private static TwitchCclDefinition definition(String id, long... descriptorIds) {
        TwitchCclDefinition definition = new TwitchCclDefinition();
        definition.setId(id);
        for (long descriptorId : descriptorIds) {
            IgdbRatingDescriptor descriptor = new IgdbRatingDescriptor();
            descriptor.setId(descriptorId);
            definition.getIgdbMappings().add(descriptor);
        }
        return definition;
    }

    @Nested
    class ExternalAppIds {
        @Test
        void routesBySourceType() {
            cached("steam:10", "1", "Steam Game");
            cached("xbox:XB", "2", "Xbox Game");

            assertThat(service.findByExternalAppId(GameBinding.SourceType.STEAM, "10"))
                    .contains(new IgdbService.IgdbGame("1", "Steam Game"));
            assertThat(service.findByExternalAppId(GameBinding.SourceType.XBOX, "XB"))
                    .contains(new IgdbService.IgdbGame("2", "Xbox Game"));
            assertThat(service.findByExternalAppId(GameBinding.SourceType.MANUAL, "10")).isEmpty();
        }

        @Test
        void xbox_externalIdHit_needsTheNameInMemory() {
            when(externalIds.findBySourceIdAndUid(XBOX, "XB")).thenReturn(Optional.of(new IgdbGameExternalId("5", XBOX, "XB")));
            when(igdb.findExternalGameByUid("XB", XBOX, "token")).thenReturn(List.of(external("XB", 5, "Forza")));

            assertThat(service.findByXboxAppId("XB")).contains(new IgdbService.IgdbGame("5", "Forza"));
            assertThat(lookups("xbox", "miss")).isEqualTo(1);

            assertThat(service.findByXboxAppId("XB")).contains(new IgdbService.IgdbGame("5", "Forza"));
            assertThat(lookups("xbox", "hit")).isEqualTo(1);
            assertThat(service.getNameIndex()).containsKey("forza");
            verify(cache).save(any(IgdbGameCacheEntry.class));
        }

        @Test
        void xbox_dbHitAndMisses() {
            cached("xbox:A", "7", "Halo");
            assertThat(service.findByXboxAppId("A")).contains(new IgdbService.IgdbGame("7", "Halo"));
            assertThat(lookups("xbox", "db_hit")).isEqualTo(1);

            when(igdb.findExternalGameByUid("B", XBOX, "token")).thenReturn(List.of());
            assertThat(service.findByXboxAppId("B")).isEmpty();

            noToken();
            assertThat(service.findByXboxAppId("C")).isEmpty();

            blankClientId();
            assertThat(service.findByXboxAppId("D")).isEmpty();
        }

        @Test
        void xbox_unresolvedSource_skipsTheExternalIdTable() {
            ReflectionTestUtils.setField(service, "xboxSourceId", -1L);
            when(igdb.findExternalGameByUid("A", -1, "token")).thenReturn(List.of(external("A", 1, "G")));

            assertThat(service.findByXboxAppId("A")).isPresent();
            verify(externalIds, never()).findBySourceIdAndUid(anyLong(), anyString());
        }

        @Test
        void steam_withoutToken_isEmpty() {
            noToken();
            assertThat(service.findBySteamAppId("10")).isEmpty();
        }

        @Test
        void steam_unresolvedSource_skipsTheExternalIdTable() {
            ReflectionTestUtils.setField(service, "steamSourceId", -1L);
            when(igdb.findExternalGameByUid("10", -1, "token")).thenReturn(List.of(external("10", 1, "G")));

            assertThat(service.findBySteamAppId("10")).contains(new IgdbService.IgdbGame("1", "G"));
            verify(cache).save(any(IgdbGameCacheEntry.class));
        }

        @Test
        void expiredDbEntries_areIgnored() {
            IgdbGameCacheEntry stale = new IgdbGameCacheEntry("xbox:A", "7", "Halo");
            stale.setCachedAt(Instant.now().minusSeconds(25 * 3600));
            when(cache.findById("xbox:A")).thenReturn(Optional.of(stale));
            when(igdb.findExternalGameByUid("A", XBOX, "token")).thenReturn(List.of());

            assertThat(service.findByXboxAppId("A")).isEmpty();
            verify(igdb).findExternalGameByUid("A", XBOX, "token");
        }
    }

    @Nested
    class NameAndExecutable {
        @Test
        void name_dbHit_isIndexed() {
            cached("name:doom", "7", "DOOM");

            assertThat(service.findByName("  Doom ")).contains(new IgdbService.IgdbGame("7", "DOOM"));
            assertThat(service.getNameIndex()).containsKey("doom");
            assertThat(lookups("name", "db_hit")).isEqualTo(1);
        }

        @Test
        void name_withoutToken_isEmpty() {
            noToken();
            assertThat(service.findByName("Doom")).isEmpty();
        }

        @Test
        void exe_dbHit_isIndexed() {
            cached("exe:doom.exe", "7", "DOOM");

            assertThat(service.findByWindowsExecutable(" DOOM.EXE ")).contains(new IgdbService.IgdbGame("7", "DOOM"));
            assertThat(service.getExeIndex()).containsKey("doom.exe");
            assertThat(lookups("exe", "db_hit")).isEqualTo(1);
        }

        @Test
        void exe_igdbHit_feedsEveryIndex() {
            when(igdb.findByWindowsExecutable("doom.exe", "token")).thenReturn(List.of(
                    AlternativeName.newBuilder().setGame(game(7, "DOOM")).build()));

            assertThat(service.findByWindowsExecutable("doom")).contains(new IgdbService.IgdbGame("7", "DOOM"));
            assertThat(service.getExeIndex()).containsKey("doom.exe");
            assertThat(service.getGameCache()).containsEntry("7", "DOOM");
            assertThat(service.getNameIndex()).containsKey("doom");
        }

        @Test
        void exe_withoutToken_isEmpty() {
            noToken();
            assertThat(service.findByWindowsExecutable("doom.exe")).isEmpty();
        }

        @Test
        void bindings_resolveBySteamAppThenName() {
            GameBinding steam = new GameBinding();
            steam.setSourceType(GameBinding.SourceType.STEAM);
            steam.setSourceId("10");
            steam.setSourceName("Portal");
            cached("steam:10", "3", "Portal");
            assertThat(service.resolveIgdbIdForBinding(steam)).contains("3");

            GameBinding manual = new GameBinding();
            manual.setSourceType(GameBinding.SourceType.MANUAL);
            manual.setSourceName("Portal 2");
            cached("name:portal 2", "4", "Portal 2");
            assertThat(service.resolveIgdbIdForBinding(manual)).contains("4");

            GameBinding steamWithoutId = new GameBinding();
            steamWithoutId.setSourceType(GameBinding.SourceType.STEAM);
            steamWithoutId.setSourceName("Portal 2");
            assertThat(service.resolveIgdbIdForBinding(steamWithoutId)).contains("4");
        }
    }

    @Nested
    class SteamPrewarm {
        @Test
        void fetchesOnlyUncachedApps() {
            when(externalIds.findBySourceIdAndUid(STEAM, "1")).thenReturn(Optional.of(new IgdbGameExternalId("100", STEAM, "1")));
            cached("steam:2", "200", "Two");
            when(igdb.findExternalGamesByUids(List.of("3", "4"), STEAM, "token"))
                    .thenReturn(List.of(external("3", 300, "Three")));

            service.prewarmSteamAppIds(List.of("1", "2", "3", "4"));

            assertThat(service.getGameCache()).containsEntry("300", "Three");
            assertThat(service.getNameIndex()).containsKey("three");
            ArgumentCaptor<IgdbGameCacheEntry> saved = ArgumentCaptor.forClass(IgdbGameCacheEntry.class);
            verify(cache).save(saved.capture());
            assertThat(saved.getValue().getLookupKey()).isEqualTo("steam:3");
        }

        @Test
        void allCachedOrNoToken_skipsIgdb() {
            cached("steam:1", "100", "One");
            service.prewarmSteamAppIds(List.of("1"));

            noToken();
            service.prewarmSteamAppIds(List.of("2"));

            verify(igdb, never()).findExternalGamesByUids(anyList(), anyLong(), anyString());
        }
    }

    @Nested
    class Search {
        @Test
        void trimsTheQuery() {
            when(igdb.searchByName("doom", "token")).thenReturn(List.of(game(7, "DOOM")));

            assertThat(service.searchGames("  doom  ")).containsExactly(new IgdbService.IgdbGame("7", "DOOM"));
        }
    }

    @Nested
    class CclSuggestions {
        private final Game violent = Game.newBuilder().setId(7).setName("DOOM")
                .addAgeRatings(rating("PEGI", "18", descriptor(29, "Violence"), descriptor(0, "ignored")))
                .addAgeRatings(rating("ESRB", "M", descriptor(30, "Blood and Gore")))
                .addAgeRatings(rating("", "X"))
                .build();

        @Test
        void mappedDescriptors_andSteamCcls_areStoredWithTheRatingLabel() {
            when(cclDefinitions.findAll()).thenReturn(List.of(definition("ViolentGraphic", 29), definition("Gambling", 99)));
            when(igdb.fetchGameById(eq("7"), anyString(), eq("token"))).thenReturn(List.of(violent));
            when(externalIds.findByIgdbIdAndSourceId("7", STEAM)).thenReturn(Optional.of(new IgdbGameExternalId("7", STEAM, "379720")));
            when(steamStore.fetchCcls(List.of("379720"))).thenReturn(Map.of("379720", Set.of("ProfanityVulgarity")));

            assertThat(service.suggestCcls("7")).containsExactlyInAnyOrder("ViolentGraphic", "ProfanityVulgarity");

            ArgumentCaptor<IgdbGameCcl> stored = ArgumentCaptor.forClass(IgdbGameCcl.class);
            verify(ccls).save(stored.capture());
            assertThat(stored.getValue().getAgeRatings()).isEqualTo("PEGI 18, ESRB M");
            assertThat(stored.getValue().getDescriptorIdsJson()).contains("29").contains("30").doesNotContain("0,");
            assertThat(service.getCclCache()).containsKey("7");
            assertThat(service.suggestCcls("7")).containsExactlyInAnyOrder("ViolentGraphic", "ProfanityVulgarity");
        }

        @Test
        void withoutAdminMappings_descriptionsAreMatchedByKeyword() {
            when(cclDefinitions.findAll()).thenReturn(List.of());
            when(igdb.fetchGameById(eq("7"), anyString(), eq("token"))).thenReturn(List.of(violent));

            assertThat(service.suggestCcls("7")).containsExactly("ViolentGraphic");
        }

        @Test
        void steamAppWithoutCcls_andNoSteamLink() {
            when(cclDefinitions.findAll()).thenReturn(List.of());
            when(igdb.fetchGameById(eq("7"), anyString(), eq("token"))).thenReturn(List.of(game(7, "DOOM")));
            when(externalIds.findByIgdbIdAndSourceId("7", STEAM)).thenReturn(Optional.of(new IgdbGameExternalId("7", STEAM, "1")));
            assertThat(service.suggestCcls("7")).isEmpty();

            ReflectionTestUtils.setField(service, "steamSourceId", -1L);
            when(igdb.fetchGameById(eq("8"), anyString(), eq("token"))).thenReturn(List.of(game(8, "Tetris")));
            assertThat(service.suggestCcls("8")).isEmpty();
            verify(steamStore).fetchCcls(List.of("1"));
        }

        @Test
        void unknownGameOrNoToken_isEmpty() {
            when(igdb.fetchGameById(eq("7"), anyString(), eq("token"))).thenReturn(List.of());
            assertThat(service.suggestCcls("7")).isEmpty();

            noToken();
            assertThat(service.suggestCcls("8")).isEmpty();
            verify(ccls, never()).save(any());
        }

        @Test
        void evictions() {
            when(igdb.fetchGameById(eq("7"), anyString(), eq("token"))).thenReturn(List.of(game(7, "DOOM")));
            when(cclDefinitions.findAll()).thenReturn(List.of());
            service.suggestCcls("7");

            assertThat(service.evictCclCache("7")).isTrue();
            assertThat(service.evictCclCache("7")).isFalse();
            assertThat(service.evictGameCache("x")).isFalse();
            assertThat(service.evictNameIndex("x")).isFalse();
            assertThat(service.evictExeIndex("x")).isFalse();
        }
    }

    @Nested
    class CclPrewarm {
        private void known(String... ids) {
            when(cache.findByCachedAtAfter(any(Instant.class))).thenReturn(
                    java.util.Arrays.stream(ids).map(id -> new IgdbGameCacheEntry("name:" + id, id, "Game " + id)).toList());
        }

        @Test
        void loadsUncachedGamesInBatches_withTheirSteamCcls() {
            known("1", "2", "3");
            when(ccls.findAllIgdbIds()).thenReturn(Set.of("3"));
            when(externalIds.findBySourceIdAndIgdbIdIn(eq(STEAM), anyCollection()))
                    .thenReturn(List.of(new IgdbGameExternalId("1", STEAM, "440")));
            when(igdb.fetchGamesByIds(anyList(), anyString(), eq("token"))).thenReturn(List.of(game(1, "One"), game(2, "Two")));
            when(steamStore.fetchCcls(List.of("440"))).thenReturn(Map.of("440", Set.of("Gambling")));
            when(cclDefinitions.findAll()).thenReturn(List.of());

            service.prewarmCclCache();

            assertThat(service.getCclCache()).containsEntry("1", Set.of("Gambling")).containsEntry("2", Set.of());
            assertThat(service.getCclCache()).doesNotContainKey("3");
        }

        @Test
        void withoutSteamSource_noSteamEnrichment() {
            ReflectionTestUtils.setField(service, "steamSourceId", -1L);
            known("1");
            when(ccls.findAllIgdbIds()).thenReturn(Set.of());
            when(igdb.fetchGamesByIds(anyList(), anyString(), eq("token"))).thenReturn(List.of(game(1, "One")));

            service.prewarmCclCache();

            verify(externalIds, never()).findBySourceIdAndIgdbIdIn(anyLong(), anyCollection());
            assertThat(service.getCclCache()).containsKey("1");
        }

        @Test
        void nothingToLoad_orNoToken_skipsIgdb() {
            when(cache.findByCachedAtAfter(any(Instant.class))).thenReturn(List.of());
            service.prewarmCclCache();

            known("1");
            when(ccls.findAllIgdbIds()).thenReturn(Set.of("1"));
            service.prewarmCclCache();

            when(ccls.findAllIgdbIds()).thenReturn(Set.of());
            noToken();
            service.prewarmCclCache();

            verify(igdb, never()).fetchGamesByIds(anyList(), anyString(), anyString());
        }
    }

    @Nested
    class DescriptorIds {
        @Test
        void badCachedJson_fallsBackToIgdb() {
            IgdbGameCcl entry = new IgdbGameCcl("7", Set.of(), "");
            entry.setDescriptorIdsJson("not json");
            when(ccls.findById("7")).thenReturn(Optional.of(entry));
            when(igdb.fetchGameById(eq("7"), anyString(), eq("token"))).thenReturn(List.of(Game.newBuilder()
                    .addAgeRatings(rating("PEGI", "18", descriptor(29, "Violence"), descriptor(0, "none"))).build()));

            assertThat(service.fetchDescriptorIds("7")).containsExactly(29L);
        }

        @Test
        void cacheWithoutJson_andIgdbMisses() {
            when(ccls.findById("7")).thenReturn(Optional.of(new IgdbGameCcl("7", Set.of(), "")));
            when(igdb.fetchGameById(eq("7"), anyString(), eq("token"))).thenReturn(List.of());
            assertThat(service.fetchDescriptorIds("7")).isEmpty();

            when(igdb.fetchGameById(eq("8"), anyString(), eq("token"))).thenThrow(new IllegalStateException("boom"));
            assertThat(service.fetchDescriptorIds("8")).isEmpty();
        }

        @Test
        void blankClientId_isEmpty() {
            blankClientId();
            assertThat(service.fetchDescriptorIds("7")).isEmpty();
            verifyNoInteractions(igdb);
        }
    }

    @Nested
    class Startup {
        @Test
        void resolvesSources_andWarmsTheCacheFromTheDb() throws RequestException {
            when(igdb.findSourcesByName("Steam", "token")).thenReturn(List.of(ExternalGameSource.newBuilder().setId(1).build()));
            when(igdb.findSourcesByName("Microsoft", "token")).thenReturn(List.of());
            when(igdb.findSourcesByName("Twitch", "token")).thenThrow(new IllegalStateException("down"));
            when(cache.findByCachedAtAfter(any(Instant.class))).thenReturn(List.of(
                    new IgdbGameCacheEntry("name:doom", "7", "DOOM"),
                    new IgdbGameCacheEntry("steam:10", "7", "DOOM (Steam)"),
                    new IgdbGameCacheEntry("exe:portal.exe", "8", "Portal")));

            service.init();

            assertThat(ReflectionTestUtils.getField(service, "steamSourceId")).isEqualTo(1L);
            assertThat(ReflectionTestUtils.getField(service, "xboxSourceId")).isEqualTo(-1L);
            assertThat(service.getTwitchSourceId()).isEqualTo(-1L);
            assertThat(service.getNameIndex()).containsOnlyKeys("doom");
            assertThat(service.getGameCache()).containsEntry("7", "DOOM").containsEntry("8", "Portal");
        }

        @Test
        void missingCredentialsOrToken_leaveSourcesUnresolved() {
            when(cache.findByCachedAtAfter(any(Instant.class))).thenReturn(List.of());
            ReflectionTestUtils.setField(service, "clientSecret", "");
            service.init();

            ReflectionTestUtils.setField(service, "clientSecret", "secret");
            noToken();
            service.init();

            verifyNoInteractions(igdb);
            assertThat(service.getTwitchSourceId()).isEqualTo(-1L);
        }

        @Test
        void twitchGameIds_comeFromTheExternalIdTable() {
            when(externalIds.findByIgdbIdAndSourceId("7", TWITCH)).thenReturn(Optional.of(new IgdbGameExternalId("7", TWITCH, "tw-7")));

            assertThat(service.findTwitchGameId("7")).contains("tw-7");
            assertThat(service.findTwitchGameId("8")).isEmpty();
        }

        @Test
        void tokenAccessors() {
            assertThat(service.getAppToken()).isEqualTo("token");
            assertThat(service.getTokenExpiresAt()).isAfter(Instant.now());
        }
    }
}
