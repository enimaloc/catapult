package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tells the dashboards that a game's DTDD mapping changed (validated, proposed, a proposal approved or
 * rejected). A mapping belongs to an IGDB game, not to a channel: every channel whose last known game is
 * that one hears of it, the way the dashboard finds the game it shows the mapping of
 * (ChannelDashboardAssembler#dtddMapping). The event carries nothing but the channel, its dashboards
 * reading the mapping again: inside a transaction it waits for the commit, for them to read the new one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DtddMappingEvents {

    private final GameStateService gameStateService;
    private final IgdbService igdbService;
    private final ChannelEventPublisher channelEventPublisher;

    /** The mapping of IGDB game {@code igdbId} changed. */
    public void mappingChanged(String igdbId) {
        if (igdbId == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(igdbId);
                }
            });
        } else {
            publish(igdbId);
        }
    }

    private void publish(String igdbId) {
        for (Map.Entry<UUID, DetectedGame> entry : gameStateService.lastKnownGames().entrySet()) {
            try {
                if (igdbGameId(entry.getValue()).filter(igdbId::equals).isPresent()) {
                    channelEventPublisher.dtddMappingChanged(entry.getKey());
                }
            } catch (RuntimeException e) {
                log.warn("No DTDD mapping event for channel {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /** The IGDB game of a detected game: by app id for Steam games, by name otherwise. */
    private Optional<String> igdbGameId(DetectedGame game) {
        return (game.getSourceType() == GameBinding.SourceType.STEAM
                ? igdbService.findBySteamAppId(game.getSourceId())
                : igdbService.findByName(game.getSourceName()))
                .map(IgdbService.IgdbGame::id);
    }
}
