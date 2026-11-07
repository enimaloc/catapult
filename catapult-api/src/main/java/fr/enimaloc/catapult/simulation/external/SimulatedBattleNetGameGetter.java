package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.BattleNetGameGetter;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The Battle.net presence in the account's {@code battlenet.presence} mode: the simulated data's game, or nothing on
 * a failure. A subclass only because the detection chain asks for the concrete getter.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = {"catapult.simulation.enabled", "battlenet.enabled"})
public class SimulatedBattleNetGameGetter extends BattleNetGameGetter {

    private final ObjectProvider<BattleNetGameGetter> real;
    private final SimulatedCallRunner runner;
    private final SimulationRegistry registry;

    public SimulatedBattleNetGameGetter(@Qualifier("battleNetGameGetter") ObjectProvider<BattleNetGameGetter> real,
                                        SimulatedCallRunner runner, SimulationRegistry registry) {
        this.real = real;
        this.runner = runner;
        this.registry = registry;
    }

    @Override
    public String name() {
        return "Battle.net";
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        return PresenceDetection.detect(runner, registry, SimulatedCalls.BATTLENET_PRESENCE,
                GetterConfig.Provider.BATTLENET, GameBinding.SourceType.BATTLENET, user,
                () -> real().getCurrentGame(user));
    }

    private BattleNetGameGetter real() {
        BattleNetGameGetter getter = real.getIfAvailable();
        if (getter == null) {
            throw new IllegalStateException("The real Battle.net getter is not available");
        }
        return getter;
    }
}
