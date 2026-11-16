package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.service.epic.EpicGateService;
import fr.enimaloc.catapult.service.epic.EpicSessionManager;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Detects the current game via Epic Connect: the streamer is friends with one of the bots, whose session receives
 * their presence (pushed, kept in memory by {@link EpicSessionManager}). No per-cycle batch: reading the in-memory
 * state costs nothing.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("epic.enabled")
public class EpicGameGetter implements GameGetter {

    private final EpicSessionManager sessions;
    private final EpicFriendLinkRepository linkRepository;
    private final EpicGateService gateService;
    private final ObjectProvider<SimulationGate> simulationGate;

    @Override
    public String name() {
        return "epic";
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        if (!gateService.isAvailableReadOnly(user)
                || SimulationGate.blocks(simulationGate, user.getId(), SimulatedCalls.EPIC_PRESENCE)) {
            return Optional.empty();
        }
        return linkRepository.findByUser(user)
                .filter(link -> link.getStatus() == EpicFriendLink.Status.ACCEPTED)
                .filter(link -> link.getEpicAccountId() != null)
                .flatMap(link -> sessions.currentGame(link.getEpicAccountId()))
                .map(game -> new DetectedGame(game.sourceId(), GameBinding.SourceType.EPIC, game.name()));
    }
}
