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
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import proto.ExternalGame;
import proto.Game;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link IgdbService#resolveDetected}: a mapping's IGDB game first, never a name search for Battle.net. */
class IgdbServiceDetectedGameTest {

    private static final long STEAM = 1;

    private final IgdbClient igdb = mock(IgdbClient.class);
    private final IgdbGameCacheRepository cache = mock(IgdbGameCacheRepository.class);
    private final IgdbGameExternalIdRepository externalIds = mock(IgdbGameExternalIdRepository.class);
    private final SteamStoreService steamStore = mock(SteamStoreService.class);
    private final GameMappingRepository mappings = mock(GameMappingRepository.class);
    private final IgdbService service = spy(new IgdbService(igdb, cache, externalIds,
            mock(IgdbGameCclRepository.class), mock(TwitchCclDefinitionRepository.class), steamStore,
            mock(RestClient.class), new SimpleMeterRegistry(), SimulationGates.none(), mappings));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "clientId", "client");
        ReflectionTestUtils.setField(service, "clientSecret", "secret");
        ReflectionTestUtils.setField(service, "cacheTtlHours", 24);
        ReflectionTestUtils.setField(service, "steamSourceId", STEAM);
        ReflectionTestUtils.setField(service, "appAccessToken", "token");
        ReflectionTestUtils.setField(service, "tokenExpiresAt", Instant.now().plusSeconds(3600));
        when(cache.findById(anyString())).thenReturn(Optional.empty());
        when(externalIds.findBySourceIdAndUid(anyLong(), anyString())).thenReturn(Optional.empty());
        when(steamStore.resolveEffectiveApp(anyString())).thenReturn(Optional.empty());
        when(mappings.findBySourceTypeAndSourceId(any(), anyString())).thenReturn(Optional.empty());
    }

    private void mapped(GameBinding.SourceType sourceType, String sourceId, String igdbId) {
        GameMapping mapping = new GameMapping();
        mapping.setSourceType(sourceType);
        mapping.setSourceId(sourceId);
        mapping.setIgdbId(igdbId);
        when(mappings.findBySourceTypeAndSourceId(sourceType, sourceId)).thenReturn(Optional.of(mapping));
    }

    @SuppressWarnings("unchecked")
    private void knownName(String igdbId, String name) {
        ((Map<String, String>) ReflectionTestUtils.getField(service, "igdbGameCache")).put(igdbId, name);
    }

    @Test
    void mappedIgdbIdWinsOverPlatformAndName() {
        mapped(GameBinding.SourceType.STEAM, "570", "123");
        knownName("123", "Mapped Game");

        assertThat(service.resolveDetected(GameBinding.SourceType.STEAM, "570", "Dota 2"))
                .contains(new IgdbService.IgdbGame("123", "Mapped Game"));

        verify(service, never()).findBySteamAppId(anyString());
        verify(service, never()).findByName(anyString());
        verify(igdb, never()).searchByName(anyString(), anyString());
        verify(igdb, never()).findExternalGameByUid(anyString(), anyLong(), anyString());
    }

    @Test
    void battleNetWithoutMappingIsNotSearchedByName() {
        assertThat(service.resolveDetected(GameBinding.SourceType.BATTLENET, "Pro", "Overwatch")).isEmpty();

        verify(service, never()).findByName(anyString());
        verifyNoInteractions(igdb);
    }

    @Test
    void battleNetMappingWithoutIgdbIdIsNotSearchedByName() {
        mapped(GameBinding.SourceType.BATTLENET, "Pro", null);

        assertThat(service.resolveDetected(GameBinding.SourceType.BATTLENET, "Pro", "Overwatch")).isEmpty();

        verify(service, never()).findByName(anyString());
        verifyNoInteractions(igdb);
    }

    @Test
    void steamWithoutMappingKeepsAppIdThenName() {
        when(igdb.findExternalGameByUid("570", STEAM, "token")).thenReturn(List.of());
        when(igdb.searchByName("Dota 2", "token")).thenReturn(List.of(Game.newBuilder().setId(7).setName("Dota 2").build()));

        assertThat(service.resolveDetected(GameBinding.SourceType.STEAM, "570", "Dota 2"))
                .contains(new IgdbService.IgdbGame("7", "Dota 2"));

        InOrder order = inOrder(igdb);
        order.verify(igdb).findExternalGameByUid("570", STEAM, "token");
        order.verify(igdb).searchByName("Dota 2", "token");
    }

    @Test
    void steamWithoutMappingStopsAtTheAppId() {
        when(igdb.findExternalGameByUid("570", STEAM, "token")).thenReturn(List.of(ExternalGame.newBuilder()
                .setGame(Game.newBuilder().setId(7).setName("Dota 2")).build()));

        assertThat(service.resolveDetected(GameBinding.SourceType.STEAM, "570", "Dota 2"))
                .contains(new IgdbService.IgdbGame("7", "Dota 2"));

        verify(igdb, never()).searchByName(anyString(), anyString());
    }

    @Test
    void resolveIgdbIdForBindingUsesTheMapping() {
        mapped(GameBinding.SourceType.BATTLENET, "Pro", "456");
        knownName("456", "Overwatch 2");
        GameBinding binding = new GameBinding();
        binding.setSourceType(GameBinding.SourceType.BATTLENET);
        binding.setSourceId("Pro");
        binding.setSourceName("Overwatch");

        assertThat(service.resolveIgdbIdForBinding(binding)).contains("456");

        verify(service, never()).findByName(anyString());
    }

    @Test
    void mappedGameNameIsFetchedOnceThenRemembered() {
        mapped(GameBinding.SourceType.BATTLENET, "Pro", "456");
        when(igdb.fetchGameById(eq("456"), anyString(), eq("token")))
                .thenReturn(List.of(Game.newBuilder().setId(456).setName("Overwatch 2").build()));

        assertThat(service.resolveDetected(GameBinding.SourceType.BATTLENET, "Pro", "Overwatch"))
                .contains(new IgdbService.IgdbGame("456", "Overwatch 2"));
        assertThat(service.resolveDetected(GameBinding.SourceType.BATTLENET, "Pro", "Overwatch"))
                .contains(new IgdbService.IgdbGame("456", "Overwatch 2"));

        verify(igdb, times(1)).fetchGameById(eq("456"), anyString(), eq("token"));
        verify(cache).save(any(IgdbGameCacheEntry.class));
        verify(igdb, never()).searchByName(anyString(), anyString());
    }

    @Test
    void mappedGameKeepsItsIdWhenIgdbIsUnavailable() {
        mapped(GameBinding.SourceType.BATTLENET, "Pro", "456");
        ReflectionTestUtils.setField(service, "clientId", "");

        assertThat(service.resolveDetected(GameBinding.SourceType.BATTLENET, "Pro", "Overwatch"))
                .contains(new IgdbService.IgdbGame("456", null));

        verifyNoInteractions(igdb);
    }
}
