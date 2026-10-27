package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.common.dto.channel.DtddMappingCurrentDto;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddGameMappingRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddMappingProposalRepository;
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
 * (ChannelDashboardAssembler#dtddMapping). The event carries the mapping as it is now and how many
 * proposals are pending on it, read once for all those channels: inside a transaction it waits for the
 * commit, to read the new one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DtddMappingEvents {

    private final GameStateService gameStateService;
    private final IgdbService igdbService;
    private final ChannelEventPublisher channelEventPublisher;
    private final DtddGameMappingRepository mappings;
    private final DtddGameCacheRepository games;
    private final DtddMappingProposalRepository proposals;

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
        DtddMappingCurrentDto current = null;
        long pending = 0;
        boolean read = false;
        for (Map.Entry<UUID, DetectedGame> entry : gameStateService.lastKnownGames().entrySet()) {
            try {
                if (igdbGameId(entry.getValue()).filter(igdbId::equals).isPresent()) {
                    if (!read) {
                        current = current(igdbId);
                        pending = proposals.countByIgdbIdAndStatus(igdbId, DtddMappingProposal.Status.PENDING);
                        read = true;
                    }
                    channelEventPublisher.dtddMappingChanged(entry.getKey(), igdbId, current, pending);
                }
            } catch (RuntimeException e) {
                log.warn("No DTDD mapping event for channel {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /** The mapping of {@code igdbId} as the dashboard shows it, null when it has none. */
    private DtddMappingCurrentDto current(String igdbId) {
        return mappings.findById(igdbId).map(mapping -> new DtddMappingCurrentDto(mapping.getDtddId(),
                mapping.getDtddId() == null ? null
                        : games.findById(mapping.getDtddId()).map(DtddGameCache::getName).orElse(null),
                mapping.getConfidence(), mapping.isVerified())).orElse(null);
    }

    /** The IGDB game of a detected game: by app id for Steam games, by name otherwise. */
    private Optional<String> igdbGameId(DetectedGame game) {
        return (game.getSourceType() == GameBinding.SourceType.STEAM
                ? igdbService.findBySteamAppId(game.getSourceId())
                : igdbService.findByName(game.getSourceName()))
                .map(IgdbService.IgdbGame::id);
    }
}
