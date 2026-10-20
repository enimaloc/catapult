package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
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
    private final DtddMappingEvents events = new DtddMappingEvents(gameState, igdb, publisher);

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
    void tellsEveryChannelPlayingTheGame_andOnlyThem() {
        playing();

        events.mappingChanged("100");

        verify(publisher).dtddMappingChanged(steamPlayer);
        verify(publisher).dtddMappingChanged(namedPlayer);
        verify(publisher, never()).dtddMappingChanged(otherPlayer);
    }

    @Test
    void insideATransaction_waitsForTheCommit() {
        playing();
        TransactionSynchronizationManager.initSynchronization();

        events.mappingChanged("200");
        verifyNoInteractions(publisher);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(publisher).dtddMappingChanged(otherPlayer);
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
