package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.domain.battlenet.BattleNetGameVariant;
import fr.enimaloc.catapult.service.battlenet.BattleNetGameVariants;
import fr.enimaloc.catapult.service.battlenet.BattleNetGateService;
import fr.enimaloc.catapult.service.battlenet.BattleNetSessionManager;
import fr.enimaloc.catapult.service.battlenet.BattleNetTitles;
import fr.enimaloc.catapult.service.debug.DetectionTrace;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Detects the current game via Battle.net: the streamer is friends with one of the bots, whose BGS session receives
 * their presence (pushed, kept in memory by {@link BattleNetSessionManager}). No per-cycle batch: reading the
 * in-memory state costs nothing. A game whose activity text matches an admin's variant rule
 * ({@link BattleNetGameVariants}) is detected as that variant (e.g. "WoW:Forever"), with bindings of its own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BattleNetGameGetter implements GameGetter {

    private final BattleNetSessionManager sessions;
    private final BattleNetFriendLinkRepository linkRepository;
    private final BattleNetGateService gateService;
    private final ObjectProvider<SimulationGate> simulationGate;
    private final BattleNetGameVariants variants;

    @Override
    public String name() {
        return "Battle.net";
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        if (!gateService.isAvailableReadOnly(user)) {
            DetectionTrace.reason("gate closed");
            return Optional.empty();
        }
        if (SimulationGate.blocks(simulationGate, user.getId(), SimulatedCalls.BATTLENET_PRESENCE)) {
            DetectionTrace.reason("blocked by the simulation");
            return Optional.empty();
        }
        Optional<BattleNetFriendLink> found = linkRepository.findByUser(user);
        if (found.isEmpty()) {
            DetectionTrace.reason("no friend link");
            return Optional.empty();
        }
        BattleNetFriendLink link = found.get();
        if (link.getStatus() != BattleNetFriendLink.Status.ACCEPTED) {
            DetectionTrace.reason("friend link is " + link.getStatus().name());
            traceSession(link);
            return Optional.empty();
        }
        Optional<BattleNetSessionManager.PlayedGame> played = sessions.currentGame(link.getBnetAccountId());
        if (played.isEmpty()) {
            DetectionTrace.reason("no game in the presence");
            traceSession(link);
        }
        return played.map(this::detected);
    }

    /** The game itself, or its variant when its activity text matches one. */
    private DetectedGame detected(BattleNetSessionManager.PlayedGame played) {
        Optional<BattleNetGameVariant> variant = variants.resolve(played.fourcc(), played.activity());
        if (DetectionTrace.active()) {
            DetectionTrace.detail("activity", played.activity());
            DetectionTrace.detail("variant", variant.map(BattleNetGameVariant::sourceId).orElse("none"));
        }
        return variant
                .map(v -> new DetectedGame(v.sourceId(), GameBinding.SourceType.BATTLENET, v.getDisplayName()))
                .orElseGet(() -> new DetectedGame(played.fourcc(), GameBinding.SourceType.BATTLENET,
                        BattleNetTitles.displayName(played.fourcc())));
    }

    /** What the bot's session looks like and the last presence it received, for the trace of an empty answer. */
    private void traceSession(BattleNetFriendLink link) {
        if (!DetectionTrace.active()) {
            return;
        }
        try {
            if (link.getServiceAccount() != null) {
                DetectionTrace.detail("session", sessions.sessionState(link.getServiceAccount().getId()));
            }
            Optional<BattleNetSessionManager.PresenceSeen> seen = sessions.lastPresence(link.getBnetAccountId());
            DetectionTrace.detail("lastPresence", seen.isEmpty()
                    ? "none received since the session opened"
                    : DetectionTrace.details("fourcc", seen.get().fourcc(), "activity", seen.get().activity(),
                            "title", seen.get().fourcc() == null ? null
                                    : BattleNetTitles.displayName(seen.get().fourcc()),
                            "at", seen.get().at()));
        } catch (RuntimeException e) {
            log.warn("Could not trace the Battle.net session: {}", e.toString());
        }
    }
}
