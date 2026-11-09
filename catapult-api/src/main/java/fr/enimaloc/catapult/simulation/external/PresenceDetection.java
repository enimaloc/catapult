package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * One presence getter's detection in the account's mode: the real getter's, or the game the simulated data has the
 * account playing on that platform. A simulated failure detects nothing, as every real getter does on a failure.
 */
@Slf4j
final class PresenceDetection {

    private PresenceDetection() {}

    static Optional<DetectedGame> detect(SimulatedCallRunner runner, SimulationRegistry registry, CallKey key,
                                         GetterConfig.Provider provider, GameBinding.SourceType sourceType,
                                         UserAccount user, Supplier<Optional<DetectedGame>> real) {
        try {
            return runner.run(user.getId(), key, "presence of " + user.getId(), real,
                    () -> Optional.ofNullable(registry.data(user.getId()).presence().get(provider))
                            .map(game -> new DetectedGame(game.sourceId(), sourceType, game.name())),
                    SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.warn("Simulated {} failed for user {}: {}", key.id(), user.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
