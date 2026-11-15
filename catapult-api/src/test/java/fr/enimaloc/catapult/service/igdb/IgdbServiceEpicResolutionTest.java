package fr.enimaloc.catapult.service.igdb;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.domain.igdb.IgdbGameCacheEntry;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameCacheRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameExternalIdRepository;
import fr.enimaloc.catapult.repository.twitch.TwitchCclDefinitionRepository;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.simulation.SimulationGates;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import proto.ExternalGame;
import proto.ExternalGameSource;
import proto.Game;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link IgdbService#resolveUnmapped} for Epic: a name candidate is kept only when the Epic catalog confirms it. */
class IgdbServiceEpicResolutionTest {

    private static final long EPIC_STORE = 26;
    private static final long OTHER_STORE = 1;
    private static final String FALL_GUYS = "d42d7101bb7a41c09a810ee418b7d3d0";
    private static final String FALL_GUYS_OFFER = "07ce78560aa34180936b199202274462";
    private static final String FALL_GUYS_TIER = FALL_GUYS + "_USD_0_1652474884058";
    private static final String GUID_UID = "c0243485-d27e-4699-aeb1-96ced18937b3";
    private static final String HOMONYM_OFFER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final IgdbClient igdb = mock(IgdbClient.class);
    private final IgdbGameCacheRepository cache = mock(IgdbGameCacheRepository.class);
    private final GameMappingRepository mappings = mock(GameMappingRepository.class);
    private final EpicCatalogClient catalog = mock(EpicCatalogClient.class);
    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    private final IgdbService service = spy(new IgdbService(igdb, cache, mock(IgdbGameExternalIdRepository.class),
            mock(IgdbGameCclRepository.class), mock(TwitchCclDefinitionRepository.class),
            mock(SteamStoreService.class), mock(RestClient.class), new SimpleMeterRegistry(),
            SimulationGates.none(), mappings, beans.getBeanProvider(EpicCatalogClient.class)));

    @BeforeEach
    void setUp() {
        beans.addBean("epicCatalogClient", catalog);
        ReflectionTestUtils.setField(service, "clientId", "client");
        ReflectionTestUtils.setField(service, "clientSecret", "secret");
        ReflectionTestUtils.setField(service, "cacheTtlHours", 24);
        ReflectionTestUtils.setField(service, "epicSourceId", EPIC_STORE);
        ReflectionTestUtils.setField(service, "appAccessToken", "token");
        ReflectionTestUtils.setField(service, "tokenExpiresAt", Instant.now().plusSeconds(3600));
        when(cache.findById(anyString())).thenReturn(Optional.empty());
        when(mappings.findBySourceTypeAndSourceId(any(), anyString())).thenReturn(Optional.empty());
    }

    private static Game game(long id, String name, Object... sourceAndUids) {
        Game.Builder builder = Game.newBuilder().setId(id).setName(name);
        for (int i = 0; i < sourceAndUids.length; i += 2) {
            builder.addExternalGames(ExternalGame.newBuilder()
                    .setExternalGameSource(ExternalGameSource.newBuilder().setId((Long) sourceAndUids[i]))
                    .setUid((String) sourceAndUids[i + 1]));
        }
        return builder.build();
    }

    private void candidates(String name, Game... games) {
        when(igdb.searchByNameWithExternalGames(name, "token")).thenReturn(List.of(games));
    }

    private void nameMatch(String name, Game game) {
        when(igdb.searchByName(name, "token")).thenReturn(List.of(game));
    }

    @Test
    void fallGuys_candidateWhoseOfferTierStartsWithTheProductId_isVerifiedAndCached() {
        candidates("Fall Guys", game(1, "Fall Guys: Ultimate Knockout", OTHER_STORE, "1097150"),
                game(2, "Fall Guys", EPIC_STORE, FALL_GUYS_OFFER, EPIC_STORE, GUID_UID));
        when(catalog.priceTiers(List.of(FALL_GUYS_OFFER))).thenReturn(Map.of(FALL_GUYS_OFFER, FALL_GUYS_TIER));

        assertThat(service.resolveUnmapped(GameBinding.SourceType.EPIC, FALL_GUYS, "Fall Guys"))
                .contains(new IgdbService.IgdbGame("2", "Fall Guys"));

        ArgumentCaptor<IgdbGameCacheEntry> saved = ArgumentCaptor.forClass(IgdbGameCacheEntry.class);
        verify(cache).save(saved.capture());
        assertThat(saved.getValue().getLookupKey()).isEqualTo("epic:" + FALL_GUYS);
        assertThat(saved.getValue().getIgdbId()).isEqualTo("2");
        verify(service, never()).findByName(anyString());
    }

    @Test
    void homonym_withAnotherProductsOffer_isDiscarded() {
        candidates("Fall Guys", game(1, "Fall Guys (homonym)", EPIC_STORE, HOMONYM_OFFER),
                game(2, "Fall Guys", EPIC_STORE, FALL_GUYS_OFFER));
        when(catalog.priceTiers(List.of(HOMONYM_OFFER, FALL_GUYS_OFFER))).thenReturn(Map.of(
                HOMONYM_OFFER, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb_USD_0_1",
                FALL_GUYS_OFFER, FALL_GUYS_TIER));

        assertThat(service.resolveUnmapped(GameBinding.SourceType.EPIC, FALL_GUYS, "Fall Guys"))
                .contains(new IgdbService.IgdbGame("2", "Fall Guys"));
    }

    @Test
    void guidUids_areIgnored() {
        candidates("Fall Guys", game(2, "Fall Guys", EPIC_STORE, GUID_UID));
        nameMatch("Fall Guys", game(2, "Fall Guys"));

        assertThat(service.resolveUnmapped(GameBinding.SourceType.EPIC, FALL_GUYS, "Fall Guys"))
                .contains(new IgdbService.IgdbGame("2", "Fall Guys"));

        verifyNoInteractions(catalog);
        verify(cache, never()).save(argThat(e -> e.getLookupKey().startsWith("epic:")));
    }

    @Test
    void noOpenSession_fallsBackToTheNameMatch_withoutCaching() {
        candidates("Fall Guys", game(2, "Fall Guys", EPIC_STORE, FALL_GUYS_OFFER));
        when(catalog.priceTiers(anyCollection())).thenReturn(Map.of());
        nameMatch("Fall Guys", game(9, "Fall Guys Name Match"));

        assertThat(service.resolveUnmapped(GameBinding.SourceType.EPIC, FALL_GUYS, "Fall Guys"))
                .contains(new IgdbService.IgdbGame("9", "Fall Guys Name Match"));

        verify(cache, never()).save(argThat(e -> e.getLookupKey().startsWith("epic:")));
    }

    @Test
    void noCatalogClient_fallsBackToTheNameMatch() {
        IgdbService withoutEpic = new IgdbService(igdb, cache, mock(IgdbGameExternalIdRepository.class),
                mock(IgdbGameCclRepository.class), mock(TwitchCclDefinitionRepository.class),
                mock(SteamStoreService.class), mock(RestClient.class), new SimpleMeterRegistry(),
                SimulationGates.none(), mappings,
                new StaticListableBeanFactory().getBeanProvider(EpicCatalogClient.class));
        ReflectionTestUtils.setField(withoutEpic, "clientId", "client");
        ReflectionTestUtils.setField(withoutEpic, "clientSecret", "secret");
        ReflectionTestUtils.setField(withoutEpic, "cacheTtlHours", 24);
        ReflectionTestUtils.setField(withoutEpic, "epicSourceId", EPIC_STORE);
        ReflectionTestUtils.setField(withoutEpic, "appAccessToken", "token");
        ReflectionTestUtils.setField(withoutEpic, "tokenExpiresAt", Instant.now().plusSeconds(3600));
        candidates("Fall Guys", game(2, "Fall Guys", EPIC_STORE, FALL_GUYS_OFFER));
        nameMatch("Fall Guys", game(9, "Fall Guys Name Match"));

        assertThat(withoutEpic.resolveUnmapped(GameBinding.SourceType.EPIC, FALL_GUYS, "Fall Guys"))
                .contains(new IgdbService.IgdbGame("9", "Fall Guys Name Match"));
        verify(cache, never()).save(argThat(e -> e.getLookupKey().startsWith("epic:")));
    }

    @Test
    void cachedEpicKey_skipsIgdbAndTheCatalog() {
        when(cache.findById("epic:" + FALL_GUYS)).thenReturn(Optional.of(
                new IgdbGameCacheEntry("epic:" + FALL_GUYS, "2", "Fall Guys")));

        assertThat(service.resolveUnmapped(GameBinding.SourceType.EPIC, FALL_GUYS, "Fall Guys"))
                .contains(new IgdbService.IgdbGame("2", "Fall Guys"));

        verifyNoInteractions(igdb, catalog);
    }

    @Test
    void adminMapping_winsOverEverything() {
        GameMapping mapping = new GameMapping();
        mapping.setSourceType(GameBinding.SourceType.EPIC);
        mapping.setSourceId(FALL_GUYS);
        mapping.setIgdbId("77");
        when(mappings.findBySourceTypeAndSourceId(GameBinding.SourceType.EPIC, FALL_GUYS))
                .thenReturn(Optional.of(mapping));
        when(cache.findById("id:77")).thenReturn(Optional.of(new IgdbGameCacheEntry("id:77", "77", "Mapped")));

        assertThat(service.resolveDetected(GameBinding.SourceType.EPIC, FALL_GUYS, "Fall Guys"))
                .contains(new IgdbService.IgdbGame("77", "Mapped"));

        verifyNoInteractions(igdb, catalog);
        verify(service, never()).resolveUnmapped(any(), anyString(), anyString());
    }

    @Test
    void nonEosNamespace_goesStraightToTheNameMatch() {
        nameMatch("Old Game", game(5, "Old Game"));

        assertThat(service.resolveUnmapped(GameBinding.SourceType.EPIC, "ns:abc", "Old Game"))
                .contains(new IgdbService.IgdbGame("5", "Old Game"));

        verifyNoInteractions(catalog);
        verify(cache, never()).findById("epic:ns:abc");
        verify(igdb, never()).searchByNameWithExternalGames(anyString(), anyString());
    }

    @Test
    void battlenetAndAnkama_stillSkipNameSearch() {
        assertThat(service.resolveUnmapped(GameBinding.SourceType.BATTLENET, "Pro", "Overwatch")).isEmpty();
        assertThat(service.resolveUnmapped(GameBinding.SourceType.ANKAMA, "3", "Wakfu")).isEmpty();

        verify(service, never()).findByName(anyString());
        verifyNoInteractions(igdb, catalog);
    }
}
