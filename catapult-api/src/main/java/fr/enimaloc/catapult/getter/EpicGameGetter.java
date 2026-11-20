package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.service.epic.EpicGame;
import fr.enimaloc.catapult.service.debug.DetectionTrace;
import fr.enimaloc.catapult.service.epic.EpicGateService;
import fr.enimaloc.catapult.service.epic.EpicSessionManager;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Detects the current game via Epic Connect: the streamer is friends with one of the bots, whose session receives
 * their presence (pushed, kept in memory by {@link EpicSessionManager}). No per-cycle batch: reading the in-memory
 * state costs nothing.
 */
@Slf4j
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
        if (!gateService.isAvailableReadOnly(user)) {
            DetectionTrace.reason("gate closed");
            return Optional.empty();
        }
        if (SimulationGate.blocks(simulationGate, user.getId(), SimulatedCalls.EPIC_PRESENCE)) {
            DetectionTrace.reason("blocked by the simulation");
            return Optional.empty();
        }
        Optional<EpicFriendLink> found = linkRepository.findByUser(user);
        if (found.isEmpty()) {
            DetectionTrace.reason("no friend link");
            return Optional.empty();
        }
        EpicFriendLink link = found.get();
        if (link.getStatus() != EpicFriendLink.Status.ACCEPTED) {
            DetectionTrace.reason("friend link is " + link.getStatus().name());
            traceSession(link);
            return Optional.empty();
        }
        if (link.getEpicAccountId() == null) {
            DetectionTrace.reason("friend link has no account id yet");
            traceSession(link);
            return Optional.empty();
        }
        Optional<EpicGame> game = sessions.currentGame(link.getEpicAccountId());
        if (game.isEmpty()) {
            DetectionTrace.reason("no game in the presence");
            traceSession(link);
        }
        return game.map(g -> new DetectedGame(g.sourceId(), GameBinding.SourceType.EPIC, g.name()));
    }

    /** What the bot's session looks like, for the trace of an empty answer. */
    private void traceSession(EpicFriendLink link) {
        if (!DetectionTrace.active() || link.getServiceAccount() == null) {
            return;
        }
        try {
            DetectionTrace.detail("session", sessions.sessionState(link.getServiceAccount().getId()));
        } catch (RuntimeException e) {
            log.warn("Could not trace the Epic session: {}", e.toString());
        }
    }
}
