package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.service.battlenet.BattleNetGateService;
import fr.enimaloc.catapult.service.battlenet.BattleNetSessionManager;
import fr.enimaloc.catapult.service.battlenet.BattleNetTitles;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Detects the current game via Battle.net: the streamer is friends with one of the bots, whose BGS session receives
 * their presence (pushed, kept in memory by {@link BattleNetSessionManager}). No per-cycle batch: reading the
 * in-memory state costs nothing.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BattleNetGameGetter implements GameGetter {

    private final BattleNetSessionManager sessions;
    private final BattleNetFriendLinkRepository linkRepository;
    private final BattleNetGateService gateService;
    private final ObjectProvider<SimulationGate> simulationGate;

    @Override
    public String name() {
        return "Battle.net";
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        if (!gateService.isAvailableReadOnly(user)
                || SimulationGate.blocks(simulationGate, user.getId(), SimulatedCalls.BATTLENET_PRESENCE)) {
            return Optional.empty();
        }
        return linkRepository.findByUser(user)
                .filter(link -> link.getStatus() == BattleNetFriendLink.Status.ACCEPTED)
                .flatMap(link -> sessions.currentGame(link.getBnetAccountId()))
                .map(fourcc -> new DetectedGame(fourcc, GameBinding.SourceType.BATTLENET,
                        BattleNetTitles.displayName(fourcc)));
    }
}
