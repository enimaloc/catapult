package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.service.ankama.AnkamaGateService;
import fr.enimaloc.catapult.service.ankama.AnkamaSessionManager;
import fr.enimaloc.catapult.service.ankama.AnkamaTitles;
import fr.enimaloc.catapult.service.debug.DetectionTrace;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Detects the current game via the Ankama launcher chat: the streamer is friends with one of the bots, whose chat
 * session receives their presence (pushed, kept in memory by {@link AnkamaSessionManager}). No per-cycle batch:
 * reading the in-memory state costs nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("ankama.enabled")
public class AnkamaGameGetter implements GameGetter {

    private final AnkamaSessionManager sessions;
    private final AnkamaFriendLinkRepository linkRepository;
    private final AnkamaGateService gateService;
    private final ObjectProvider<SimulationGate> simulationGate;

    @Override
    public String name() {
        return "ankama";
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        if (!gateService.isAvailableReadOnly(user)) {
            DetectionTrace.reason("gate closed");
            return Optional.empty();
        }
        if (SimulationGate.blocks(simulationGate, user.getId(), SimulatedCalls.ANKAMA_PRESENCE)) {
            DetectionTrace.reason("blocked by the simulation");
            return Optional.empty();
        }
        Optional<AnkamaFriendLink> found = linkRepository.findByUser(user);
        if (found.isEmpty()) {
            DetectionTrace.reason("no friend link");
            return Optional.empty();
        }
        AnkamaFriendLink link = found.get();
        if (link.getStatus() != AnkamaFriendLink.Status.ACCEPTED) {
            DetectionTrace.reason("friend link is " + link.getStatus().name());
            traceSession(link);
            return Optional.empty();
        }
        if (link.getAnkamaUserId() == null) {
            DetectionTrace.reason("friend link has no account id yet");
            traceSession(link);
            return Optional.empty();
        }
        OptionalInt game = sessions.currentGame(link.getAnkamaUserId());
        if (game.isEmpty()) {
            DetectionTrace.reason("no game in the presence");
            traceSession(link);
            return Optional.empty();
        }
        int id = game.getAsInt();
        return Optional.of(new DetectedGame(String.valueOf(id), GameBinding.SourceType.ANKAMA,
                AnkamaTitles.displayName(id)));
    }

    /** What the bot's session looks like, for the trace of an empty answer. */
    private void traceSession(AnkamaFriendLink link) {
        if (!DetectionTrace.active() || link.getServiceAccount() == null) {
            return;
        }
        try {
            DetectionTrace.detail("session", sessions.sessionState(link.getServiceAccount().getId()));
        } catch (RuntimeException e) {
            log.warn("Could not trace the Ankama session: {}", e.toString());
        }
    }
}
