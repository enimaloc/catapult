package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.common.dto.channel.DtddMappingCurrentDto;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddGameMapping;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddGameMappingRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddMappingProposalRepository;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

/** DtddMappingEvents: which channels hear of a mapping's change, and when. */
class DtddMappingEventsTest {

    private final GameStateService gameState = mock(GameStateService.class);
    private final IgdbService igdb = mock(IgdbService.class);
    private final ChannelEventPublisher publisher = mock(ChannelEventPublisher.class);
    private final DtddGameMappingRepository mappings = mock(DtddGameMappingRepository.class);
    private final DtddGameCacheRepository games = mock(DtddGameCacheRepository.class);
    private final DtddMappingProposalRepository proposals = mock(DtddMappingProposalRepository.class);
    private final DtddMappingEvents events = new DtddMappingEvents(gameState, igdb, publisher, mappings, games, proposals);

    private final UUID steamPlayer = UUID.randomUUID();
    private final UUID namedPlayer = UUID.randomUUID();
    private final UUID otherPlayer = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    private void playing() {
        when(gameState.lastKnownGames()).thenReturn(Map.of(
                steamPlayer, new DetectedGame("413150", GameBinding.SourceType.STEAM, "Stardew Valley"),
                namedPlayer, new DetectedGame(null, GameBinding.SourceType.MANUAL, "Stardew Valley"),
                otherPlayer, new DetectedGame("620", GameBinding.SourceType.STEAM, "Portal 2")));
        when(igdb.findBySteamAppId("413150")).thenReturn(Optional.of(new IgdbService.IgdbGame("100", "Stardew Valley")));
        when(igdb.findByName("Stardew Valley")).thenReturn(Optional.of(new IgdbService.IgdbGame("100", "Stardew Valley")));
        when(igdb.findBySteamAppId("620")).thenReturn(Optional.of(new IgdbService.IgdbGame("200", "Portal 2")));
    }

    @Test
    void tellsEveryChannelPlayingTheGame_andOnlyThem_theMappingAsItIsNow() {
        playing();
        DtddGameMapping mapping = new DtddGameMapping();
        mapping.setIgdbId("100");
        mapping.setDtddId(7L);
        mapping.setConfidence(0.8);
        mapping.setVerified(true);
        when(mappings.findById("100")).thenReturn(Optional.of(mapping));
        DtddGameCache cached = new DtddGameCache();
        cached.setDtddId(7L);
        cached.setName("Stardew Valley (2016)");
        when(games.findById(7L)).thenReturn(Optional.of(cached));
        when(proposals.countByIgdbIdAndStatus("100", DtddMappingProposal.Status.PENDING)).thenReturn(2L);

        events.mappingChanged("100");

        DtddMappingCurrentDto current = new DtddMappingCurrentDto(7L, "Stardew Valley (2016)", 0.8, true);
        verify(publisher).dtddMappingChanged(steamPlayer, "100", current, 2L);
        verify(publisher).dtddMappingChanged(namedPlayer, "100", current, 2L);
        verify(publisher, never()).dtddMappingChanged(eq(otherPlayer), any(), any(), anyLong());
        // Read once for every channel playing it
        verify(mappings).findById("100");
        verify(proposals).countByIgdbIdAndStatus("100", DtddMappingProposal.Status.PENDING);
    }

    @Test
    void aGameWithoutMapping_orAMappingWithoutDtddGame_isToldAsSuch() {
        playing();

        events.mappingChanged("200");
        DtddGameMapping unnamed = new DtddGameMapping();
        unnamed.setIgdbId("100");
        when(mappings.findById("100")).thenReturn(Optional.of(unnamed));
        events.mappingChanged("100");

        verify(publisher).dtddMappingChanged(otherPlayer, "200", null, 0L);
        verify(publisher).dtddMappingChanged(eq(steamPlayer), eq("100"),
                eq(new DtddMappingCurrentDto(null, null, unnamed.getConfidence(), false)), eq(0L));
        verifyNoInteractions(games);
    }

    @Test
    void insideATransaction_waitsForTheCommit() {
        playing();
        TransactionSynchronizationManager.initSynchronization();

        events.mappingChanged("200");
        verifyNoInteractions(publisher);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(publisher).dtddMappingChanged(otherPlayer, "200", null, 0L);
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void aGameIgdbCantResolve_isSkipped() {
        when(gameState.lastKnownGames()).thenReturn(Map.of(steamPlayer,
                new DetectedGame("1", GameBinding.SourceType.STEAM, "Unknown")));
        when(igdb.findBySteamAppId("1")).thenThrow(new IllegalStateException("IGDB down"));

        events.mappingChanged("100");
        events.mappingChanged(null);

        verifyNoInteractions(publisher);
    }
}
