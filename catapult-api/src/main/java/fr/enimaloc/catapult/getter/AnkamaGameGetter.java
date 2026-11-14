package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.service.ankama.AnkamaGateService;
import fr.enimaloc.catapult.service.ankama.AnkamaSessionManager;
import fr.enimaloc.catapult.service.ankama.AnkamaTitles;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Detects the current game via the Ankama launcher chat: the streamer is friends with one of the bots, whose chat
 * session receives their presence (pushed, kept in memory by {@link AnkamaSessionManager}). No per-cycle batch:
 * reading the in-memory state costs nothing.
 */
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
        if (!gateService.isAvailableReadOnly(user)
                || SimulationGate.blocks(simulationGate, user.getId(), SimulatedCalls.ANKAMA_PRESENCE)) {
            return Optional.empty();
        }
        return linkRepository.findByUser(user)
                .filter(link -> link.getStatus() == AnkamaFriendLink.Status.ACCEPTED)
                .filter(link -> link.getAnkamaUserId() != null)
                .flatMap(link -> sessions.currentGame(link.getAnkamaUserId()).stream().boxed().findFirst())
                .map(id -> new DetectedGame(String.valueOf(id), GameBinding.SourceType.ANKAMA,
                        AnkamaTitles.displayName(id)));
    }
}
